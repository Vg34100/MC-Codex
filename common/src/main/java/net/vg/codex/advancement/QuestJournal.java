package net.vg.codex.advancement;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.*;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** Serial server-thread transactions. Recovery never grants an item or rerolls a loot table. */
public final class QuestJournal {
    private QuestJournal() { }
    private static Path journal(MinecraftServer server) { return QuestState.directory(server).resolve("codex/transaction.json"); }
    private static String diskReceipt(MinecraftServer server, UUID player) throws IOException {
        Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player + ".dat");
        if (!Files.exists(file)) return "";
        return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getStringOr("codex_transaction", "");
    }
    public static void recover(MinecraftServer server) {
        Path file = journal(server); if (!Files.exists(file)) return;
        try {
            JsonObject record = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            String saved = diskReceipt(server, UUID.fromString(record.get("player").getAsString()));
            String after = record.get("receipt").getAsString(), before = record.get("before").getAsString();
            if (saved.equals(after)) finish(server, record);
            else if (!saved.equals(before)) throw new IllegalStateException("Player receipt conflicts with the pending transaction; operator recovery is required");
            // Before receipt: no inventory change was saved, so the unacknowledged action aborts.
            Files.deleteIfExists(file);
        } catch (IOException error) { throw new IllegalStateException("Cannot recover pending quest transaction", error); }
    }
    private static Path target(MinecraftServer server, String relative) {
        Path root = QuestState.directory(server).toAbsolutePath().normalize(), path = root.resolve(relative).normalize();
        if (!path.startsWith(root.resolve("codex"))) throw new IllegalArgumentException("Transaction state must stay inside this world's codex directory");
        return path;
    }
    private static void finish(MinecraftServer server, JsonObject record) {
        for (JsonElement e : record.getAsJsonArray("updates")) {
            JsonObject update = e.getAsJsonObject(); AtomicJson.write(target(server, update.get("path").getAsString()), update.get("value"));
        }
    }
    public static JsonObject mailbox(ServerPlayer player) {
        Path path = QuestState.directory(player.level().getServer()).resolve("codex/mailbox/" + player.getUUID() + ".json");
        try { return Files.exists(path) ? JsonParser.parseString(Files.readString(path)).getAsJsonObject() : new JsonObject(); }
        catch (IOException error) { throw new IllegalStateException("Cannot read stored rewards", error); }
    }
    public static boolean collect(ServerPlayer player) {
        Plan plan = new Plan(player); JsonArray old = plan.mail.deepCopy(); plan.mail = new JsonArray();
        for (JsonElement e : old) plan.add(plan.decode(e));
        if (old.equals(plan.mail)) return false;
        plan.commit(); return true;
    }
    public static final class Plan {
        private final ServerPlayer player;
        private final MinecraftServer server;
        private final RegistryOps<JsonElement> ops;
        private final List<ItemStack> before = new ArrayList<>(), inventory = new ArrayList<>();
        private final JsonArray updates = new JsonArray();
        private JsonArray mail;
        private int experience;
        public Plan(ServerPlayer player) {
            this.player = player; server = player.level().getServer(); recover(server);
            ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) { before.add(player.getInventory().getItem(i).copy()); inventory.add(before.get(i).copy()); }
            JsonObject box = mailbox(player); mail = box.has("items") ? box.getAsJsonArray("items").deepCopy() : new JsonArray();
        }
        private ItemStack decode(JsonElement e) { return ItemStack.OPTIONAL_CODEC.parse(ops, e).getOrThrow(); }
        public void remove(Map<Integer, Integer> slots) {
            slots.forEach((slot, count) -> {
                if (slot < 0 || slot >= inventory.size() || count < 0 || inventory.get(slot).getCount() < count) throw new IllegalArgumentException("Inventory changed before submission");
                inventory.get(slot).shrink(count);
            });
        }
        public void add(ItemStack reward) {
            ItemStack stack = reward.copy(); if (stack.isEmpty()) return;
            for (int i = 0; i < 36 && !stack.isEmpty(); i++) {
                ItemStack existing = inventory.get(i);
                if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
                    int moved = Math.min(stack.getCount(), Math.max(0, existing.getMaxStackSize() - existing.getCount())); existing.grow(moved); stack.shrink(moved);
                }
            }
            for (int i = 0; i < 36 && !stack.isEmpty(); i++) if (inventory.get(i).isEmpty()) {
                int moved = Math.min(stack.getCount(), stack.getMaxStackSize()); inventory.set(i, stack.copyWithCount(moved)); stack.shrink(moved);
            }
            while (!stack.isEmpty()) {
                if (mail.size() >= 4096) throw new IllegalArgumentException("Stored reward capacity reached; collect rewards first");
                int moved = Math.min(stack.getCount(), stack.getMaxStackSize()); mail.add(ItemStack.CODEC.encodeStart(ops, stack.copyWithCount(moved)).getOrThrow()); stack.shrink(moved);
            }
        }
        public void experience(int value) { if (value < 0 || value > 1_000_000) throw new IllegalArgumentException("Invalid XP reward"); experience = Math.addExact(experience, value); }
        public void update(Path file, JsonElement json) {
            Path root = QuestState.directory(server).toAbsolutePath().normalize();
            String relative = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
            Path resolved = target(server, relative);
            if (Files.exists(resolved) && !Files.isRegularFile(resolved)) throw new IllegalStateException("Quest state path is not a file");
            try { Files.createDirectories(resolved.getParent()); Path probe = Files.createTempFile(resolved.getParent(), ".codex-probe-", ".tmp"); Files.delete(probe); }
            catch (IOException e) { throw new IllegalStateException("Quest state is not writable", e); }
            JsonObject entry = new JsonObject(); entry.addProperty("path", relative); entry.add("value", json.deepCopy()); updates.add(entry);
        }
        public void commit() {
            // Every parse, random roll and capacity check happens before this point.
            JsonObject box = new JsonObject(); box.add("items", mail);
            update(QuestState.directory(server).resolve("codex/mailbox/" + player.getUUID() + ".json"), box);
            TransactionReceipt receipt = (TransactionReceipt) player;
            String previous = receipt.codex$receipt(), next = UUID.randomUUID().toString();
            JsonObject record = new JsonObject(); record.addProperty("player", player.getUUID().toString()); record.addProperty("before", previous); record.addProperty("receipt", next); record.add("updates", updates);
            // Persist the generated outcomes too, for inspection; recovery only applies sidecars.
            JsonArray items = new JsonArray(); for (ItemStack stack : inventory) items.add(ItemStack.OPTIONAL_CODEC.encodeStart(ops, stack).getOrThrow()); record.add("inventory", items); record.addProperty("xp", experience);
            AtomicJson.write(journal(server), record);
            int xp = player.totalExperience, level = player.experienceLevel; float progress = player.experienceProgress;
            boolean committed = false;
            try {
                for (int i = 0; i < inventory.size(); i++) player.getInventory().setItem(i, inventory.get(i).copy());
                player.giveExperiencePoints(experience); receipt.codex$receipt(next);
                server.getPlayerList().saveAll();
                Path data = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID() + ".dat");
                try (FileChannel channel = FileChannel.open(data, StandardOpenOption.WRITE)) { channel.force(true); }
                committed = diskReceipt(server, player.getUUID()).equals(next);
                if (!committed) throw new IllegalStateException("Vanilla player save did not persist the transaction receipt");
                finish(server, record); Files.deleteIfExists(journal(server));
            } catch (IOException | RuntimeException failure) {
                // If a save succeeded before an error, recovery must finish its sidecar changes.
                try { committed |= diskReceipt(server, player.getUUID()).equals(next); }
                catch (IOException unreadable) { failure.addSuppressed(unreadable); committed = true; }
                if (!committed) {
                    for (int i = 0; i < before.size(); i++) player.getInventory().setItem(i, before.get(i));
                    player.totalExperience = xp; player.experienceLevel = level; player.experienceProgress = progress; receipt.codex$receipt(previous);
                    try { Files.deleteIfExists(journal(server)); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw new IllegalStateException("Quest transaction needs recovery before another action", failure);
            } finally { player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); }
        }
    }
}
