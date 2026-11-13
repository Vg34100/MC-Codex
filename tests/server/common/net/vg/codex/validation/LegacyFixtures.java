package net.vg.codex.validation;

import com.google.gson.*;
import net.vg.codex.advancement.*;
import java.nio.file.*;

/** Converts only the disposable test definitions, never a player's authored pack. */
final class LegacyFixtures {
    private LegacyFixtures() { }
    static void adapt(Path pack) {
        try(var files=Files.walk(pack.resolve("data"))) {
            for(Path source:files.filter(Files::isRegularFile).toList()) {
                String relative=pack.relativize(source).toString().replace('\\','/');
                String nativePath=ResourceFormats.relative(relative);Path target=pack.resolve(nativePath);
                if(nativePath.contains("/advancements/") && source.toString().endsWith(".json")) {
                    JsonObject document=JsonParser.parseString(Files.readString(source)).getAsJsonObject();
                    if(relative.startsWith("data/minecraft/")) {
                        try(var input=net.minecraft.advancements.Advancement.class.getResourceAsStream("/"+nativePath)) {
                            if(input==null)throw new IllegalStateException("Missing native fixture "+nativePath);
                            JsonObject base=JsonParser.parseString(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
                            if(document.has("display"))base.add("display",document.get("display"));document=base;
                        }
                    } else if(document.has("criteria"))for(var entry:document.getAsJsonObject("criteria").entrySet())entry.setValue(CriterionFormats.generated(entry.getValue().getAsJsonObject()));
                    AtomicJson.write(target,ResourceFormats.advancement(document));
                    if(!target.equals(source))Files.delete(source);
                } else if(!target.equals(source)) {Files.createDirectories(target.getParent());Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);}
            }
        } catch(Exception error) {throw new IllegalStateException("Cannot prepare legacy test fixtures",error);}
    }
}
