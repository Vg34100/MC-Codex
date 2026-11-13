package net.vg.codex.client.advancements;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.*;
import net.minecraft.world.item.*;
import net.vg.codex.advancement.QuestRewards.RewardType;
import net.vg.codex.advancement.TreasureBagItem;

/** Human labels and actual item models are shared by lists, dialogs and quest details. */
public final class ClientRewardPresentation {
    private ClientRewardPresentation() { }
    public static String poolName(Identifier table) {
        Identifier key = table.getPath().startsWith("codex_treasure/") ? Identifier.fromNamespaceAndPath(table.getNamespace(), table.getPath().substring("codex_treasure/".length())) : table;
        JsonObject lists = ClientTreasureLists.lists();
        return lists.has(key.toString()) ? lists.getAsJsonObject(key.toString()).get("name").getAsString() : pretty(table);
    }
    public static String pretty(Identifier id) {
        String path = id.getPath(); path = path.substring(path.lastIndexOf('/') + 1).replace('_', ' ');
        StringBuilder result = new StringBuilder();
        for (String word : path.split(" ")) if (!word.isEmpty()) { if (!result.isEmpty()) result.append(' '); result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); }
        return result.toString();
    }
    public static ItemStack icon(JsonObject reward) {
        try {
            return switch (RewardType.valueOf(reward.get("type").getAsString())) {
                case EXPERIENCE -> new ItemStack(Items.EXPERIENCE_BOTTLE);
                case ITEM -> {
                    JsonObject stack = reward.deepCopy(); stack.addProperty("count", 1);
                    yield ItemStack.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, Minecraft.getInstance().level.registryAccess()), stack).getOrThrow();
                }
                case TREASURE_BAG -> TreasureBagItem.create(Identifier.parse(reward.get("id").getAsString()), 1, reward.has("bag_name") ? reward.get("bag_name").getAsString() : "Treasure Bag", reward.has("bag_model") ? Identifier.parse(reward.get("bag_model").getAsString()) : null);
                case RECIPE -> new ItemStack(Items.KNOWLEDGE_BOOK);
                case FUNCTION -> new ItemStack(Items.COMMAND_BLOCK);
                case LOOT_TABLE, RANDOM_TREASURE -> new ItemStack(Items.CHEST);
            };
        } catch (RuntimeException invalid) { return new ItemStack(Items.PAPER); }
    }
    public static String label(JsonObject reward) {
        RewardType type = RewardType.valueOf(reward.get("type").getAsString());
        return switch (type) {
            case EXPERIENCE -> reward.get("amount").getAsInt() + " XP";
            case ITEM, TREASURE_BAG -> icon(reward).getHoverName().getString() + " ×" + (reward.has("count") ? reward.get("count").getAsInt() : 1);
            case RECIPE -> "Recipe: " + pretty(Identifier.parse(reward.get("id").getAsString()));
            case RANDOM_TREASURE -> "Random Treasure: " + poolName(Identifier.parse(reward.get("id").getAsString()));
            case LOOT_TABLE -> "Treasure: " + poolName(Identifier.parse(reward.get("id").getAsString()));
            case FUNCTION -> "Datapack Function";
        };
    }
    public static String details(JsonObject reward) {
        String heading=label(reward); RewardType type=RewardType.valueOf(reward.get("type").getAsString());
        if(type!=RewardType.RANDOM_TREASURE&&type!=RewardType.LOOT_TABLE&&type!=RewardType.TREASURE_BAG)return heading;
        Identifier table=Identifier.parse(reward.get("id").getAsString());Identifier key=table.getPath().startsWith("codex_treasure/")?Identifier.fromNamespaceAndPath(table.getNamespace(),table.getPath().substring("codex_treasure/".length())):table;
        JsonObject lists=ClientTreasureLists.lists();StringBuilder out=new StringBuilder(heading).append(type==RewardType.TREASURE_BAG?"\nOpening this bag generates its contents on the server.":"\nGenerated on the server when earned or claimed.");
        if(!lists.has(key.toString()))return out.append("\nUses a Minecraft/mod loot table; contents can depend on loot conditions.").toString();
        JsonObject list=lists.getAsJsonObject(key.toString());out.append("\nRolls: ").append(list.has("rolls")?list.get("rolls").getAsString():"1");
        double total=0;for(var value:list.getAsJsonArray("entries")){JsonObject e=value.getAsJsonObject();total+=e.has("weight")?e.get("weight").getAsInt():1;}
        for(var value:list.getAsJsonArray("entries")){
            JsonObject e=value.getAsJsonObject();int weight=e.has("weight")?e.get("weight").getAsInt():1;String item;
            if((e.has("table") && e.get("table").getAsBoolean()))item="Loot: "+poolName(Identifier.parse(e.get("id").getAsString()));
            else{Identifier itemId=Identifier.parse(e.get("id").getAsString());item=new ItemStack(BuiltInRegistries.ITEM.getValue(itemId)).getHoverName().getString();}
            out.append("\n• ").append(item);if(!(e.has("table") && e.get("table").getAsBoolean())){int min=e.has("min")?e.get("min").getAsInt():1,max=e.has("max")?e.get("max").getAsInt():min;out.append(" × ").append(min);if(max!=min)out.append("–").append(max);}
            out.append(String.format(java.util.Locale.ROOT," (%.1f%% per roll)",total>0?100.0*weight/total:0));
        }
        return out.append("\nEach roll chooses one weighted entry. Previewing does not generate or reserve loot.").toString();
    }

}
