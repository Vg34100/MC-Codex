package net.vg.codex.client.advancements;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Short-lived local presentation, driven only by acknowledged server results/progress. */
public final class QuestFeedback {
    private record Pulse(String kind, long at, Set<String> items) { }
    private record Notice(String title, List<String> lines, ItemStack icon, int color, long at, boolean reward) { }
    private static final Map<Identifier, Pulse> PULSES = new LinkedHashMap<>();
    private static final Deque<Notice> NOTICES = new ArrayDeque<>();
    private static final Map<Identifier, Integer> NATIVE = new HashMap<>();
    private static Identifier held;
    private static float holding;
    private static long heldAt, soundAt;
    private QuestFeedback() { }
    public static void clear() { PULSES.clear(); NOTICES.clear(); NATIVE.clear(); ChapterFeedback.clear(); held = null; holding = 0; }
    public static void pulse(Identifier id, String kind, Set<String> items) {
        PULSES.remove(id); PULSES.put(id, new Pulse(kind, System.nanoTime(), Set.copyOf(items)));
        while (PULSES.size() > 256) PULSES.remove(PULSES.keySet().iterator().next());
    }
    public static float pulse(Identifier id, String kind) {
        if (!CodexClientSettings.animations) return 0;
        Pulse p = PULSES.get(id); if (p == null || !p.kind.equals(kind)) return 0;
        return Math.max(0, 1 - (System.nanoTime() - p.at) / 900_000_000F);
    }
    public static float itemPulse(Identifier id, ItemStack item) {
        Pulse p = PULSES.get(id); if (p == null) return 0;
        return p.items.contains(BuiltInRegistries.ITEM.getKey(item.getItem()).toString()) ? pulse(id, p.kind) : 0;
    }
    public static void hold(Identifier id, float value) { held = id; holding = Math.min(1, value); heldAt = System.nanoTime(); }
    public static float hold(Identifier id) {
        if (!id.equals(held)) return 0;
        // Releasing or dragging retreats locally; it never accepts a quest.
        return holding * Math.max(0, 1 - Math.max(0, System.nanoTime() - heldAt - 70_000_000L) / 180_000_000F);
    }
    public static void changed(Identifier id, JsonObject before, JsonObject after) {
        if (before == null) return; // Opening a menu is not an achievement.
        if (!flag(before, "ready") && flag(after, "ready")) { pulse(id, "complete", Set.of()); sound("complete"); }
        else if (!flag(before, "unlocked") && flag(after, "unlocked")) pulse(id, "unlock", Set.of());
    }
    private static boolean flag(JsonObject json, String key) { return json.has(key) && json.get(key).getAsBoolean(); }
    public static void result(Identifier id, String title, JsonObject result) {
        String action = result.has("action") ? result.get("action").getAsString() : "error";
        if (result.has("action") && result.get("action").getAsString().equals("milestone")) { ChapterFeedback.show(id,title,result); return; }
        boolean success = flag(result, "success");
        Set<String> items = result.has("items") ? result.getAsJsonObject("items").keySet() : Set.of();
        if (success) { pulse(id, action, items); sound(action); }
        var mc = Minecraft.getInstance(); var node = mc.getConnection().getAdvancements().getTree().get(id);
        ItemStack icon = node != null && node.advancement().display().isPresent() ? node.advancement().display().get().getIcon().create() : ItemStack.EMPTY;
        if (action.equals("claim_all")) icon = new ItemStack(net.minecraft.world.item.Items.CHEST);
        List<String> lines = new ArrayList<>();
        if (success && action.equals("submit")) {
            for (var item : result.getAsJsonObject("items").entrySet()) lines.add("−" + item.getValue().getAsInt() + " " + ClientQuests.itemName(item.getKey()));
            JsonObject info = ClientQuests.info(id);
            lines.add(flag(info, "ready") ? (flag(info, "all_claimed") ? "Delivery complete" : "Delivery complete · rewards ready") : ClientQuests.summary(info) + " delivered");
        } else lines.add(success ? switch (action) {
            case "accept" -> "Accepted · objectives active"; case "claim" -> "Rewards claimed";
            case "choice" -> "Story branch selected"; case "mailbox" -> "Stored rewards collected";
            default -> result.get("message").getAsString();
        } : result.get("message").getAsString());
        JsonObject info = ClientQuests.info(id);
        if (success && action.equals("claim") && info.has("stored_rewards") && info.get("stored_rewards").getAsInt() > 0) lines.add("Inventory full · collect stored rewards in quest details");
        notice(new Notice(title, lines, icon, success ? action.startsWith("claim") ? 0xFFFFD76A : 0xFF7BE3C0 : 0xFFE4B18B, System.nanoTime(), action.startsWith("claim") || action.equals("mailbox")));
    }
    public static void nativeProgress(net.minecraft.client.multiplayer.ClientAdvancements tree, net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket packet) {
        if (packet.shouldReset()) NATIVE.clear();
        packet.getRemoved().forEach(NATIVE::remove);
        for (var entry : packet.getProgress().entrySet()) {
            Identifier id = entry.getKey(); var progress = entry.getValue();
            int count = 0; for (String ignored : progress.getCompletedCriteria()) count++;
            Integer previous = NATIVE.put(id, count);
            if (packet.shouldReset() || count <= (previous == null ? 0 : previous)) continue;
            var node = tree.getTree().get(id); if (node == null || node.advancement().display().isEmpty()) continue;
            var display = node.advancement().display().get();
            JsonObject presentation = ClientQuests.presentation(id);
            // Delivery already has an exact consumed-items receipt. Recipe unlocks stay quiet.
            if (!display.shouldShowToast() || ClientSubmissionTasks.forAdvancement(id).size() > 0 || flag(presentation, "managed")) continue;
            List<Identifier> items = ClientAdvancementRequiredItems.forAdvancement(id);
            if (items.isEmpty()) continue;
            Set<String> changed = new HashSet<>(); items.forEach(item -> changed.add(item.toString()));
            pulse(id, progress.isDone() ? "complete" : "obtain", changed);
            String detail = progress.isDone() ? "Obtain objective complete" : "Obtain progress · " + count + " / " + node.advancement().requirements().names().size() + " requirements";
            notice(new Notice(display.getTitle().getString(), List.of(detail), BuiltInRegistries.ITEM.getValue(items.get(0)).getDefaultInstance(), 0xFF9CE4BB, System.nanoTime(), false));
        }
    }
    private static void notice(Notice notice) {
        NOTICES.removeIf(n -> System.nanoTime() - n.at > 4_500_000_000L);
        NOTICES.addLast(notice);
        // Claimed items can complete several obtain objectives in the same tick. Their
        // feedback must not immediately evict the receipt for the claim that caused them.
        while (NOTICES.size() > 3) {
            Notice ordinary = NOTICES.stream().filter(n -> !n.reward).findFirst().orElse(null);
            if (ordinary == null) NOTICES.removeFirst(); else NOTICES.remove(ordinary);
        }
    }
    private static void sound(String action) {
        long now = System.nanoTime(); if (!CodexClientSettings.sounds || now - soundAt < 180_000_000L) return; soundAt = now;
        var event = switch (action) {
            case "claim", "claim_all", "complete" -> SoundEvents.EXPERIENCE_ORB_PICKUP;
            case "choice" -> SoundEvents.BOOK_PAGE_TURN;
            default -> SoundEvents.ITEM_PICKUP;
        };
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(event, action.equals("submit") ? 1.15F : 1F, 0.18F));
    }
    public static void render(GuiGraphicsExtractor g) {
        render(g, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 12);
    }
    /** Keep notifications above a modal panel rather than over its controls. */
    public static void render(GuiGraphicsExtractor g, int bottomLimit) {
        var mc = Minecraft.getInstance();
        if (!CodexClientSettings.notices || mc.player == null || mc.options.hideGui) return;
        ChapterFeedback.render(g);
        long now = System.nanoTime(); NOTICES.removeIf(n -> now - n.at > 4_500_000_000L);
        int width = Math.min(230, mc.getWindow().getGuiScaledWidth() - 20), bottom = Math.min(bottomLimit, mc.getWindow().getGuiScaledHeight() - 12);
        int x = mc.getWindow().getGuiScaledWidth() - width - 10;
        for (var notices = NOTICES.descendingIterator(); notices.hasNext();) {
            Notice notice=notices.next();
            float age = (now - notice.at) / 1_000_000_000F;
            float opacity = Math.min(1, age / 0.12F) * Math.min(1, (4.5F - age) / 0.6F);
            List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
            for (String line : notice.lines) {
                for (var wrapped : mc.font.split(net.minecraft.network.chat.Component.literal(line), width - 38)) {
                    if (lines.size() >= 5) break;
                    lines.add(wrapped);
                }
            }
            int height = 20 + lines.size() * 10, y = bottom - height;
            if (y < 8) break;
            g.fill(x, y, x + width, bottom, alpha(0xC51C2526, opacity));
            g.fill(x, y, x + 2, bottom, alpha(notice.color, opacity));
            g.fakeItem(notice.icon, x + 8, y + 9);
            g.text(mc.font, mc.font.plainSubstrByWidth(notice.title, width - 38), x + 32, y + 6, alpha(0xFFFFFFFF, opacity), false);
            for (int i = 0; i < lines.size(); i++) g.text(mc.font, lines.get(i), x + 32, y + 18 + i * 10, alpha(notice.color, opacity), false);
            bottom = y - 5;
        }
    }
    private static int alpha(int color, float opacity) { return (Math.max(4, Math.round((color >>> 24) * opacity)) << 24) | (color & 0xFFFFFF); }
}
