package net.vg.codex.advancement;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;

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
        Path file = fileFor(packDir, holder.id());
        JsonElement json = Advancement.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), holder.value()).getOrThrow();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
