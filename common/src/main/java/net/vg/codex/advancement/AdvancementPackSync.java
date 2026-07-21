package net.vg.codex.advancement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.architectury.platform.Platform;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.ReloadCommand;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackFormat;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.util.InclusiveRange;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Keeps the externally-editable "codex/advancements" folder (outside the mod jar, edited by the
 * in-game editor) in sync with every world's own datapacks folder, so edits apply to any world
 * or server without the user manually copying files around.
 */
public final class AdvancementPackSync {
    public static final String PACK_ID = "codex_advancements";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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

            // ReloadCommand.reloadPacks() alone does NOT rescan disk for newly-appeared packs -
            // it only reloads content for whatever's already selected. Rediscovery only happens
            // inside vanilla's private ReloadCommand.discoverNewPacks(), which real /reload calls
            // first. On a brand new world, this pack's folder didn't exist yet when the world's
            // initial pack list was built, so without replicating that discovery step ourselves,
            // codex_advancements would never get selected for that server's entire lifetime -
            // reloadPacks would just keep re-selecting the same stale (pack-less) set forever.
            PackRepository repository = server.getPackRepository();
            repository.reload();
            List<String> disabled = server.getWorldData().getDataConfiguration().dataPacks().getDisabled();
            List<String> selected = new ArrayList<>(repository.getSelectedIds());
            for (String id : repository.getAvailableIds()) {
                if (!disabled.contains(id) && !selected.contains(id)) {
                    selected.add(id);
                }
            }
            ReloadCommand.reloadPacks(selected, server.createCommandSourceStack());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to sync codex advancement datapack", e);
        }
    }

    private static void ensurePackSkeleton() throws IOException {
        Path dir = externalPackDir();
        Files.createDirectories(dir);
        // Regenerated on every sync (not just when missing) via the game's own metadata codec,
        // rather than a hand-written pack_format integer - this MC version requires min_format/
        // max_format once the format number is past a threshold, and hand-writing that schema
        // risks going stale again on a future MC update the same way pack_format alone did.
        var sectionType = PackMetadataSection.forPackType(PackType.SERVER_DATA);
        PackFormat format = PackFormat.of(SharedConstants.DATA_PACK_FORMAT_MAJOR, SharedConstants.DATA_PACK_FORMAT_MINOR);
        PackMetadataSection section = new PackMetadataSection(
                Component.literal("Codex custom advancements"),
                new InclusiveRange<>(format)
        );
        JsonElement sectionJson = sectionType.codec().encodeStart(JsonOps.INSTANCE, section).getOrThrow();
        JsonObject root = new JsonObject();
        root.add(sectionType.name(), sectionJson);
        Files.writeString(dir.resolve("pack.mcmeta"), GSON.toJson(root), StandardCharsets.UTF_8);
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
