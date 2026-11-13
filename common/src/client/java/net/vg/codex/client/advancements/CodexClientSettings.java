package net.vg.codex.client.advancements;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.vg.codex.advancement.AtomicJson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** Client preferences only; quest progress is always supplied by the current server. */
public final class CodexClientSettings {
    private static final Path FILE = Platform.getConfigFolder().resolve("codex-client.json");
    public static int scale;
    public static boolean hud = true;
    public static boolean animations = true, holdAccept = true, notices = true, sounds = true;
    public static int hudPosition;
    public static final Set<Identifier> pins = new LinkedHashSet<>();
    static {
        try {
            if (Files.exists(FILE)) {
                JsonObject json = JsonParser.parseString(Files.readString(FILE)).getAsJsonObject();
                scale = json.has("scale") ? json.get("scale").getAsInt() : 0;
                animations = !json.has("animations") || json.get("animations").getAsBoolean();
                holdAccept = !json.has("hold_accept") || json.get("hold_accept").getAsBoolean();
                notices = !json.has("notices") || json.get("notices").getAsBoolean();
                sounds = !json.has("sounds") || json.get("sounds").getAsBoolean();
                hud = !json.has("hud") || json.get("hud").getAsBoolean();
                hudPosition = json.has("hud_position") ? Math.floorMod(json.get("hud_position").getAsInt(), 4) : 0;
                if (json.has("pins")) for (var pin : json.getAsJsonArray("pins")) {
                    if (pins.size() < 3) pins.add(Identifier.parse(pin.getAsString()));
                }
            }
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(CodexClientSettings.class).warn("Cannot read Codex client preferences; using defaults", error);
        }
    }
    private CodexClientSettings() { }

    public static int nativeScale() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getWindow().calculateScale(minecraft.options.guiScale().get(), minecraft.isEnforceUnicode());
    }

    public static int effectiveScale() {
        int nativeScale = nativeScale();
        if (scale == -1) return nativeScale;
        int desired = scale == 0 ? (nativeScale >= 3 ? nativeScale - 1 : nativeScale) : Math.max(1, Math.min(scale, nativeScale));
        // Keep the existing canvas and details together at the smallest usable GUI widths.
        while (desired > 1 && Minecraft.getInstance().getWindow().getWidth() / desired < 422) desired--;
        return desired;
    }

    public static String scaleName(int value) {
        return switch (value) { case 0 -> "Comfortable"; case -1 -> "Minecraft"; default -> "Scale " + value; };
    }

    public static void save() {
        JsonObject json = new JsonObject(); json.addProperty("scale", scale); json.addProperty("hud", hud); json.addProperty("hud_position", hudPosition);
        json.addProperty("animations", animations); json.addProperty("hold_accept", holdAccept); json.addProperty("notices", notices); json.addProperty("sounds", sounds);
        JsonArray tracked = new JsonArray(); pins.forEach(id -> tracked.add(id.toString())); json.add("pins", tracked);
        AtomicJson.write(FILE, json);
    }

    public static void pin(Identifier id) {
        if (!pins.remove(id)) {
            if (pins.size() == 3) pins.remove(pins.iterator().next());
            pins.add(id);
        }
        save();
    }
}
