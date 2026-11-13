package net.vg.codex.client.advancements;

import com.google.gson.*;
import net.minecraft.resources.Identifier;
import net.vg.codex.network.TreasureListsPayload;
import java.util.List;

public final class ClientTreasureLists {
    private static JsonObject lists = new JsonObject();
    private static List<Identifier> lootTables = List.of();
    private ClientTreasureLists() { }
    public static void set(TreasureListsPayload payload) {
        lists = JsonParser.parseString(payload.definitions()).getAsJsonObject(); lootTables = List.copyOf(payload.lootTables());
    }
    public static JsonObject lists() { return lists.deepCopy(); }
    public static List<Identifier> lootTables() { return lootTables; }
}
