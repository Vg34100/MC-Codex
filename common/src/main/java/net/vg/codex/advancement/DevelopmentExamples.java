package net.vg.codex.advancement;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Opt-in launch configuration support; no example content ships in the production jar. */
public final class DevelopmentExamples {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevelopmentExamples.class);
    private DevelopmentExamples() { }

    public static void install() {
        String directory = System.getProperty("codex.dev.examples");
        if (directory == null || !Platform.isDevelopmentEnvironment()) return;
        Path destination = AdvancementPackSync.externalPackDir();
        Path marker = destination.getParent().resolve("development_examples.json");
        JsonObject installed;
        try {
            installed = Files.exists(marker) ? JsonParser.parseString(Files.readString(marker)).getAsJsonObject() : new JsonObject();
        } catch (IOException | RuntimeException error) {
            LOGGER.error("Cannot read development example history; leaving the authoring pack untouched", error);
            return;
        }
        if (installed.has("story-demo") && !installed.has("story-consolidated-v2")) {
            try {
                boolean retired = DevelopmentExampleUpgrade.retireJournal(Path.of(directory).resolve("story-demo"), destination, destination.getParent().resolve("development_example_backups/field-journal-v1"));
                installed.addProperty("story-consolidated-v2", retired ? "untouched-journal-backed-up-and-retired" : "edited-journal-preserved");
                AtomicJson.write(marker, installed);
                LOGGER.info("Codex story consolidation: {}", installed.get("story-consolidated-v2"));
            } catch (IOException | RuntimeException error) { LOGGER.error("Could not consolidate old story example; retaining it", error); }
        }
        if (installed.has("story-consolidated-v2") && installed.get("story-consolidated-v2").getAsString().equals("edited-journal-preserved") && !installed.has("legacy-demo-labelled-v3")) {
            try {
                DevelopmentExampleUpgrade.labelPreservedJournal(Path.of(directory).resolve("story-demo"), destination, destination.getParent().resolve("development_example_backups/field-journal-v1"));
                installed.addProperty("legacy-demo-labelled-v3", true); AtomicJson.write(marker, installed);
            } catch (IOException | RuntimeException error) { LOGGER.error("Cannot label preserved legacy example", error); }
        }
        for (String example : List.of("vanilla-enhanced", "framework-showcase")) {
            int version = example.equals("framework-showcase") ? 3 : 2;
            int previous = installed.has(example) ? (installed.get(example).getAsJsonPrimitive().isBoolean() ? 1 : installed.get(example).getAsInt()) : 0;
            if (previous >= version) continue;
            Path source = Path.of(directory).resolve(example);
            if (!Files.isDirectory(source)) {
                LOGGER.warn("Missing Codex development example: {}", source);
                continue;
            }
            try (var paths = Files.walk(source)) {
                for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                    Path relative = source.relativize(file);
                    Path target = destination.resolve(relative);
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    } else if (relative.startsWith("codex") && file.toString().endsWith(".json")) {
                        // Seed absent example entries only. Editing a demo in the GUI is permanent;
                        // restarting the client must never replace an author's changes.
                        JsonObject current = JsonParser.parseString(Files.readString(target, StandardCharsets.UTF_8)).getAsJsonObject();
                        JsonObject incoming = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                        boolean changed = false;
                        for (var entry : incoming.entrySet()) {
                            if (!current.has(entry.getKey())) {
                                current.add(entry.getKey(), entry.getValue());
                                changed = true;
                            }
                        }
                        if (changed) AtomicJson.write(target, current);
                    }
                }
                installed.addProperty(example, version);
                AtomicJson.write(marker, installed);
                LOGGER.info("Codex development demo ready: {} in {} (existing edits preserved)", example, destination);
            } catch (IOException | RuntimeException error) {
                LOGGER.error("Cannot prepare Codex development example {}", example, error);
            }
        }
    }
}
