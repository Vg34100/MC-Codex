package net.vg.codex.advancement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads and writes vanilla advancement JSON (data/<namespace>/advancement/<path>.json) inside the
 * editable pack folder, going through the real {@link Advancement#CODEC} so files round-trip
 * exactly as vanilla itself would read/write them - including overwriting a vanilla advancement
 * id like minecraft:story/root.
 */
public final class AdvancementPackIO {
    private static final String ADVANCEMENT_DIR = "advancement";
    private static final String SUFFIX = ".json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private AdvancementPackIO() {
    }

    public static List<AdvancementHolder> readAll(Path packDir, HolderLookup.Provider registries) {
        List<AdvancementHolder> result = new ArrayList<>();
        Path dataDir = packDir.resolve("data");
        if (Files.notExists(dataDir)) {
            return result;
        }
        try (Stream<Path> namespaces = Files.list(dataDir)) {
            for (Path namespaceDir : namespaces.filter(Files::isDirectory).toList()) {
                String namespace = namespaceDir.getFileName().toString();
                Path advancementDir = namespaceDir.resolve(ADVANCEMENT_DIR);
                if (Files.notExists(advancementDir)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(advancementDir)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(SUFFIX)).toList()) {
                        String relative = advancementDir.relativize(file).toString().replace('\\', '/');
                        String path = relative.substring(0, relative.length() - SUFFIX.length());
                        Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
                        result.add(new AdvancementHolder(id, readOne(file, registries)));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return result;
    }

    private static Advancement readOne(Path file, HolderLookup.Provider registries) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement json = JsonParser.parseString(text);
        return Advancement.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json).getOrThrow();
    }

    public static void write(Path packDir, AdvancementHolder holder, HolderLookup.Provider registries) {
        JsonElement json = Advancement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), holder.value()).getOrThrow();
        writeJson(fileFor(packDir, holder.id()), json.getAsJsonObject());
    }

    /**
     * True if this id already resolves to a file, either in the editable pack folder or in
     * whatever datapacks/vanilla data are currently loaded.
     */
    public static boolean exists(Path packDir, ResourceProvider resources, Identifier id) {
        return Files.exists(fileFor(packDir, id)) || resources.getResource(resourceLocationFor(id)).isPresent();
    }

    /**
     * Patches only the display block of an already-existing advancement (vanilla or otherwise),
     * leaving criteria/requirements/rewards untouched as raw JSON. This deliberately avoids
     * round-tripping the whole advancement through {@link Advancement#CODEC}: some vanilla
     * advancements fail that codec's stricter encode-side validation (criteria/requirements
     * consistency) even though they loaded fine, since decode is more lenient than encode.
     * Patching JSON directly sidesteps that entirely for edits that only ever touch display info.
     */
    public static void patchDisplay(Path packDir, ResourceProvider resources, Identifier id, DisplayInfo display, HolderLookup.Provider registries) {
        JsonObject json = readBaseJson(packDir, resources, id);
        JsonElement displayJson = DisplayInfo.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), display).getOrThrow();
        json.add("display", displayJson);
        writeJson(fileFor(packDir, id), json);
    }

    private static JsonObject readBaseJson(Path packDir, ResourceProvider resources, Identifier id) {
        Path localFile = fileFor(packDir, id);
        try {
            if (Files.exists(localFile)) {
                return JsonParser.parseString(Files.readString(localFile, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            try (BufferedReader reader = resources.getResourceOrThrow(resourceLocationFor(id)).openAsReader()) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeJson(Path file, JsonObject json) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Identifier resourceLocationFor(Identifier id) {
        return Identifier.fromNamespaceAndPath(id.getNamespace(), ADVANCEMENT_DIR + "/" + id.getPath() + SUFFIX);
    }

    public static void delete(Path packDir, Identifier id) {
        try {
            Files.deleteIfExists(fileFor(packDir, id));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path fileFor(Path packDir, Identifier id) {
        return packDir.resolve("data").resolve(id.getNamespace()).resolve(ADVANCEMENT_DIR).resolve(id.getPath() + SUFFIX);
    }
}
