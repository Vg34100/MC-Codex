package net.vg.codex.advancement;

import com.google.gson.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Authored definitions are shared; claims, deliveries and parties belong to the loaded world. */
public final class QuestState {
    private QuestState() { }
    public static Path directory(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT); }

    public static void initialize(MinecraftServer server) {
        migrate(AdvancementPackSync.externalPackDir(), directory(server));
    }

    /** Never erase legacy data. Import only identities already saved in this world, once. */
    public static void migrate(Path legacy, Path world) {
        Path marker = world.resolve("codex/state_version.json");
        // A marker also means this world deliberately started independently. Never import
        // global legacy data merely because a later code version recognizes another path.
        if (Files.exists(marker)) return;
        try {
            Set<String> players = new HashSet<>();
            // 26.1.2 moved player files to players/data; still recognize upgraded legacy saves.
            for (Path playerData : List.of(world.resolve(LevelResource.PLAYER_DATA_DIR.id()), world.resolve("players/data"), world.resolve("playerdata"))) {
                if (Files.isDirectory(playerData)) try (var files = Files.list(playerData)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".dat")).forEach(path -> players.add(path.getFileName().toString().replace(".dat", "")));
                }
            }
            for (String kind : List.of("claims", "submissions")) {
                for (String player : players) copyLegacy(legacy, world, "codex/" + kind + "/" + player + ".json");
            }
            Path partyFile = legacy.resolve("codex/parties.json");
            if (!players.isEmpty() && Files.isRegularFile(partyFile) && !Files.exists(world.resolve("codex/parties.json"))) {
                JsonObject parties = JsonParser.parseString(Files.readString(partyFile)).getAsJsonObject();
                JsonObject imported = new JsonObject();
                for (var party : parties.entrySet()) {
                    JsonArray members = new JsonArray();
                    party.getValue().getAsJsonArray().forEach(member -> { if (players.contains(member.getAsString())) members.add(member); });
                    if (!members.isEmpty()) {
                        imported.add(party.getKey(), members);
                        copyLegacy(legacy, world, "codex/submissions/" + party.getKey() + ".json");
                    }
                }
                AtomicJson.write(world.resolve("codex/parties.json"), imported);
            }
            JsonObject version = new JsonObject(); version.addProperty("version", 2); version.addProperty("legacy_players_imported", players.size());
            AtomicJson.write(marker, version);
        } catch (IOException error) { throw new IllegalStateException("Cannot migrate world quest state; legacy files remain untouched", error); }
    }

    private static void copyLegacy(Path legacy, Path world, String relative) throws IOException {
        Path source = legacy.resolve(relative); Path target = world.resolve(relative);
        if (!Files.isRegularFile(source) || Files.exists(target)) return;
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
        Files.copy(source, target.resolveSibling(target.getFileName() + ".legacy.bak"));
    }
}
