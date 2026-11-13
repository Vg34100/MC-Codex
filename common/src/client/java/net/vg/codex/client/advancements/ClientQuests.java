package net.vg.codex.client.advancements;

import com.google.gson.*;
import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.*;
import java.util.*;

public final class ClientQuests {
    private static final Map<Identifier, JsonObject> INFO = new LinkedHashMap<>();
    private static final Set<Identifier> WATCHING = new LinkedHashSet<>();
    private static Object connection;
    private static final Map<Identifier, Map<Identifier, JsonObject>> TREES = new LinkedHashMap<>();
    private static final Map<Identifier, Long> READ_AT = new HashMap<>();
    private static Identifier viewed;
    private static long viewSent, requests;
    private static final long REFRESH_NANOS = 2_000_000_000L;
    public static JsonObject presentation(Identifier id) {
        connected(); return TREES.getOrDefault(viewed, Map.of()).getOrDefault(id, new JsonObject());
    }
    public static boolean locked(Identifier id) { JsonObject p = presentation(id); return p.has("unlocked") && !p.get("unlocked").getAsBoolean(); }
    public static void view(Identifier root) {
        connected();
        if (!Objects.equals(root, viewed)) {
            if (root == null && viewed != null) action(viewed, "unview", "");
            viewed = root; viewSent = 0;
            if (root != null) {
                // Retain the last acknowledged tree during navigation and a delayed refresh.
                Map<Identifier, JsonObject> cached = TREES.remove(root);
                TREES.put(root, cached == null ? new HashMap<>() : cached);
                while (TREES.size() > 8) TREES.remove(TREES.keySet().iterator().next());
            }
        }
        refreshView();
    }
    private static void refreshView() {
        long now = System.nanoTime();
        if (viewed != null && (viewSent == 0 || now - viewSent >= REFRESH_NANOS)) { viewSent = now; action(viewed, "view", ""); }
    }
    public static void receive(QuestPresentationPayload packet) {
        connected(); Map<Identifier, JsonObject> tree = TREES.get(packet.root()); if (tree == null) return;
        JsonObject incoming = JsonParser.parseString(packet.json()).getAsJsonObject();
        Map<Identifier, JsonObject> previous = new HashMap<>(tree);
        if (packet.replace()) tree.clear();
        for (var entry : incoming.entrySet()) {
            Identifier id = Identifier.parse(entry.getKey()); JsonObject next = entry.getValue().getAsJsonObject();
            if (packet.root().equals(viewed)) QuestFeedback.changed(id, previous.get(id), next);
            tree.put(id, next);
        }
    }
    private static final Map<Identifier, JsonObject> RESULTS = new LinkedHashMap<>();
    private static void connected() {
        Object next = Minecraft.getInstance().getConnection();
        if (connection != next) { connection = next; INFO.clear(); WATCHING.clear(); RESULTS.clear(); TREES.clear(); READ_AT.clear(); viewed = null; viewSent = 0; requests = 0; QuestFeedback.clear(); }
    }
    public static JsonObject result(Identifier id) { return RESULTS.getOrDefault(id, new JsonObject()); }
    public static String itemName(String id) { return net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.parse(id)).getDefaultInstance().getHoverName().getString(); }
    public static String resultText(JsonObject result) {
        if (result.isEmpty()) return "";
        StringBuilder text = new StringBuilder(result.get("message").getAsString());
        if (result.has("items")) for (var item : result.getAsJsonObject("items").entrySet()) text.append("\n− ").append(item.getValue().getAsInt()).append(" × ").append(itemName(item.getKey()));
        return text.toString();
    }
    public static void watch(Identifier id) {
        connected();
        if (!WATCHING.contains(id) || !READ_AT.containsKey(id) || System.nanoTime() - READ_AT.get(id) >= REFRESH_NANOS) retry(id);
    }
    /** Fresh read only. It never retries a reward/choice mutation whose outcome is unknown. */
    public static String retry(Identifier id) {
        connected();
        if (!WATCHING.contains(id) && WATCHING.size() >= 4) for (Identifier old : new ArrayList<>(WATCHING)) if (!CodexClientSettings.pins.contains(old)) {
            action(old, "unwatch", ""); WATCHING.remove(old); READ_AT.remove(old); break;
        }
        WATCHING.add(id); READ_AT.put(id, System.nanoTime());
        String request = Long.toString(++requests); action(id, "watch", request); return request;
    }

    private ClientQuests() { }
    public static JsonObject info(Identifier id) { connected(); return INFO.getOrDefault(id, new JsonObject()); }
    public static void receive(QuestInfoPayload packet) {
        connected();
        if (Minecraft.getInstance().getConnection() == null) return;
        JsonObject incoming = JsonParser.parseString(packet.json()).getAsJsonObject();
        if (incoming.has("definition")) {
            JsonObject previousResult = RESULTS.get(packet.id());
            if (!incoming.has("result") && previousResult != null && previousResult.has("action") && Set.of("watch", "view").contains(previousResult.get("action").getAsString())) RESULTS.remove(packet.id());
            INFO.remove(packet.id()); INFO.put(packet.id(), incoming);
            while (INFO.size() > 64) { Identifier oldest = INFO.keySet().stream().filter(i -> !WATCHING.contains(i) && !CodexClientSettings.pins.contains(i)).findFirst().orElse(null); if (oldest == null) break; INFO.remove(oldest); }
        }
        if (incoming.has("result")) {
            JsonObject result = incoming.getAsJsonObject("result"); RESULTS.put(packet.id(), result);
            while (RESULTS.size() > 16) RESULTS.remove(RESULTS.keySet().iterator().next());
            Minecraft mc = Minecraft.getInstance();
            var node = mc.getConnection().getAdvancements().getTree().get(packet.id());
            String name = node != null && node.advancement().display().isPresent() ? node.advancement().display().get().getTitle().getString() : "Codex";
            if (result.has("title")) name = result.get("title").getAsString();
            QuestFeedback.result(packet.id(), name, result);
        }
        if (Minecraft.getInstance().screen instanceof QuestDetailsScreen screen && screen.id.equals(packet.id())) screen.update();
        if (Minecraft.getInstance().screen instanceof StoryChoicesScreen screen && screen.id.equals(packet.id())) screen.refresh(incoming.has("result"));
    }

    public static void action(Identifier id, String action, String value) {
        if (Minecraft.getInstance().getConnection() != null) NetworkManager.sendToServer(new QuestActionPayload(id, action, value));
    }
    public static boolean canAccept(JsonObject info) {
        if (info.isEmpty() || !info.get("unlocked").getAsBoolean() || info.has("eligible") && !info.get("eligible").getAsBoolean()) return false;
        JsonObject q = info.getAsJsonObject("definition"), s = info.getAsJsonObject("state");
        return net.vg.codex.advancement.QuestDefinitions.managedLocal(q) && (!net.vg.codex.advancement.QuestDefinitions.flag(s, "accepted") || net.vg.codex.advancement.QuestDefinitions.repeat(q) && net.vg.codex.advancement.QuestDefinitions.flag(s, "completed") && info.get("all_claimed").getAsBoolean() && info.get("available_in").getAsLong() == 0);
    }
    public static List<String> hoverHints(Identifier id) {
        JsonObject p = presentation(id); if (p.isEmpty()) return List.of();
        List<String> hints = new ArrayList<>();
        if (p.has("reason")) { hints.add(p.get("reason").getAsString()); return hints; }
        if (p.get("can_accept").getAsBoolean()) hints.add(CodexClientSettings.holdAccept ? "● Hold icon to accept" : "● Open to accept this quest");
        if (p.get("dialogue").getAsBoolean()) hints.add(p.get("choice_pending").getAsBoolean() ? "● Click to read and choose" : "● Click to read the story");
        if (p.get("claim_pending").getAsBoolean()) hints.add(p.get("claim_ready").getAsBoolean() ? "● Click a gold reward to claim" : "● Rewards need claiming on completion");
        if (p.get("repeat").getAsBoolean()) { long wait = p.get("available_in").getAsLong(); hints.add(wait > 0 ? "● Repeatable · next cycle in " + waitText(wait) : "● Repeatable quest"); }
        return hints;
    }
    public static String waitText(long seconds) {
        if (seconds >= 3600) return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
        if (seconds >= 60) return (seconds / 60) + "m " + (seconds % 60) + "s";
        return seconds + "s";
    }
    public static String summary(JsonObject info) {
        if (info.isEmpty()) return "Loading…";
        if (!info.get("unlocked").getAsBoolean()) return "Choose the required story branch first";
        JsonObject q = info.getAsJsonObject("definition"), s = info.getAsJsonObject("state");
        if (net.vg.codex.advancement.QuestDefinitions.managedLocal(q) && !net.vg.codex.advancement.QuestDefinitions.flag(s, "accepted")) return "Accept this quest to begin";
        if (info.get("ready").getAsBoolean()) return !info.has("rewards") || info.getAsJsonObject("rewards").isEmpty() ? "Completed" : info.get("all_claimed").getAsBoolean() ? "Completed · rewards claimed" : "Completed · rewards ready";
        int done = 0, count = 0;
        for (JsonElement e : info.getAsJsonArray("tasks")) {
            JsonObject task = e.getAsJsonObject();
            if (task.has("items")) for (JsonElement item : task.getAsJsonArray("items")) { JsonObject i = item.getAsJsonObject(); done += i.get("submitted").getAsInt(); count += i.get("required").getAsInt(); }
            else if (task.has("target")) { done += Math.min(task.get("value").getAsInt(), task.get("target").getAsInt()); count += task.get("target").getAsInt(); }
            else { if (task.get("done").getAsBoolean()) done++; count++; }
        }
        return done + " / " + count + (s.has("cycle") ? " · cycle " + (s.get("cycle").getAsInt() + 1) : "");
    }
    /** Invoked by the GUI render hook even when the tracker is hidden, for connection cleanup. */
    public static void render(GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        connected();
        if (mc.player == null || mc.level == null) return;
        if (!(mc.screen instanceof CodexScreen)) { view(null); if (mc.screen == null) QuestFeedback.render(g); }
        else refreshView();
        Set<Identifier> wanted = new LinkedHashSet<>(CodexClientSettings.pins);
        if (mc.screen instanceof QuestDetailsScreen screen) wanted.add(screen.id);
        if (mc.screen instanceof StoryChoicesScreen screen) wanted.add(screen.id);
        if (mc.screen instanceof CodexAdvancementsScreen screen && screen.questGesture() != null) wanted.add(screen.questGesture());
        for (Identifier id : new HashSet<>(WATCHING)) if (!wanted.contains(id)) { action(id, "unwatch", ""); WATCHING.remove(id); READ_AT.remove(id); }
        for (Identifier id : wanted) watch(id);
        if (!CodexClientSettings.hud || CodexClientSettings.pins.isEmpty() || mc.screen != null || mc.options.hideGui) return;
        int w = Math.min(190, mc.getWindow().getGuiScaledWidth() / 2), h = 12 + CodexClientSettings.pins.size() * 28;
        int x = CodexClientSettings.hudPosition % 2 == 0 ? mc.getWindow().getGuiScaledWidth() - w - 6 : 6;
        int y = CodexClientSettings.hudPosition < 2 ? 8 : mc.getWindow().getGuiScaledHeight() - h - 48;
        g.fill(x, y, x + w, y + h, 0xB0202020); int row = y + 6;
        for (Identifier id : CodexClientSettings.pins) {
            var holder = mc.getConnection().getAdvancements().getTree().get(id);
            String title = holder != null && holder.advancement().display().isPresent() ? holder.advancement().display().get().getTitle().getString() : id.getPath();
            g.text(mc.font, mc.font.plainSubstrByWidth(title, w - 12), x + 6, row, 0xFFFFFFFF, false);
            g.text(mc.font, mc.font.plainSubstrByWidth(summary(info(id)), w - 12), x + 6, row + 11, 0xFFCCCCAA, false); row += 28;
        }
    }
}
