package net.vg.codex.advancement;

import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.vg.codex.party.CodexPartyService;
import java.nio.file.*;
import java.util.*;

/** Explicit administrator reset. Inventory, mailbox, statistics and party membership are retained. */
public final class QuestReset {
    private QuestReset() { }
    public static void everything(ServerPlayer player) {
        var server = player.level().getServer(); QuestJournal.recover(server);
        Path world = QuestState.directory(server), data = world.resolve("codex");
        UUID self = player.getUUID(), party = CodexPartyService.progressOwner(server, self);
        Set<Path> files = new LinkedHashSet<>();
        for (String relative : List.of("player_quests/" + self + ".json", "party_quests/" + party + ".json",
                "party_advancements/" + party + ".json", "submissions/" + party + ".json", "submissions/" + self + ".json",
                "claims/" + self + ".json", "claim_keys/" + self + ".json")) files.add(data.resolve(relative));
        try {
            // Clear every old cycle's ledgers too: restarting at cycle zero must not inherit claims.
            for (String directory : List.of("cycles", "shared_cycles")) {
                Path cycles = data.resolve(directory); if (!Files.isDirectory(cycles)) continue;
                try (var paths = Files.walk(cycles)) {
                    paths.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().equals((directory.equals("cycles") ? self : party) + ".json"))
                        .filter(p -> p.getParent().getFileName().toString().equals("claims") || p.getParent().getFileName().toString().equals("claim_keys"))
                        .forEach(files::add);
                }
            }
            Path backup = data.resolve("reset_backups/" + System.currentTimeMillis() + "-" + UUID.randomUUID());
            QuestJournal.Plan reset = new QuestJournal.Plan(player);
            for (Path file : files) if (Files.isRegularFile(file)) {
                Path copy = backup.resolve(data.relativize(file)); Files.createDirectories(copy.getParent()); Files.copy(file, copy);
                reset.update(file, new JsonObject());
            }
            reset.commit();
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot back up quest state before administrator reset", e); }
    }
}
