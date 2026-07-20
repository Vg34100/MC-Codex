package net.vg.codex.advancement;

import dev.architectury.platform.Platform;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.ReloadCommand;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Keeps the externally-editable "codex/advancements" folder (outside the mod jar, edited by the
 * in-game editor) in sync with every world's own datapacks folder, so edits apply to any world
 * or server without the user manually copying files around.
 */
public final class AdvancementPackSync {
    public static final String PACK_ID = "codex_advancements";

    private AdvancementPackSync() {
    }

    public static Path externalPackDir() {
        return Platform.getGameFolder().resolve("codex").resolve("advancements");
    }

    public static void onServerStarting(MinecraftServer server) {
        try {
            ensurePackSkeleton();
            Path worldPackDir = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(PACK_ID);
            copyTree(externalPackDir(), worldPackDir);
            ReloadCommand.reloadPacks(server.getPackRepository().getAvailableIds(), server.createCommandSourceStack());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to sync codex advancement datapack", e);
        }
    }

    private static void ensurePackSkeleton() throws IOException {
        Path dir = externalPackDir();
        Files.createDirectories(dir);
        Path mcmeta = dir.resolve("pack.mcmeta");
        if (Files.notExists(mcmeta)) {
            String json = """
                    {
                      "pack": {
                        "pack_format": %d,
                        "description": "Codex custom advancements"
                      }
                    }
                    """.formatted(SharedConstants.DATA_PACK_FORMAT_MAJOR);
            Files.writeString(mcmeta, json, StandardCharsets.UTF_8);
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path src : (Iterable<Path>) stream::iterator) {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
