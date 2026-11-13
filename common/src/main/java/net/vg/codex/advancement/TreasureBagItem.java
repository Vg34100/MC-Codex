package net.vg.codex.advancement;

import com.google.gson.JsonObject;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import java.util.Map;

/** One component-driven item; any treasure list or native loot table can supply its contents. */
public final class TreasureBagItem extends Item {
    public static final ResourceKey<Item> KEY = ResourceKey.create(Registries.ITEM, Identifier.parse("codex:treasure_bag"));
    public static Item ITEM;
    public TreasureBagItem() { super(new Item.Properties().setId(KEY).stacksTo(16)); }
    public static ItemStack create(Identifier pool, int count, String name, Identifier appearance) {
        if (ITEM == null) throw new IllegalStateException("Treasure bag item has not been registered");
        ItemStack stack = new ItemStack(ITEM, count);
        CompoundTag tag = new CompoundTag(); tag.putString("codex_pool", pool.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (name != null && !name.isBlank()) stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        //? if <26.1 {
        /*if (appearance != null) { tag.putString("codex_appearance",appearance.toString()); stack.set(DataComponents.CUSTOM_DATA,CustomData.of(tag)); }
        *///?} else {
        if (appearance != null) stack.set(DataComponents.ITEM_MODEL, appearance);
        //?}
        return stack;
    }
    public static boolean open(ServerPlayer player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (ITEM == null || !held.is(ITEM)) return false;
        CustomData data = held.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        Identifier pool = Identifier.tryParse(data.copyTag().getStringOr("codex_pool", ""));
        if (pool == null || !net.vg.codex.network.TreasureNetworking.lootTables(player).contains(pool)) return false;
        int slot = hand == InteractionHand.OFF_HAND ? 40 : player.getInventory().getSelectedSlot();
        QuestJournal.Plan transaction = new QuestJournal.Plan(player); transaction.remove(Map.of(slot, 1));
        JsonObject entry = new JsonObject(); entry.addProperty("type", "LOOT_TABLE"); entry.addProperty("id", pool.toString());
        QuestTransactions.planReward(player, transaction, entry); transaction.commit();
        return true;
    }
    //? if <26.1 {
    /*@Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return new InteractionResultHolder<>(useResult(level,player,hand),player.getItemInHand(hand));
    }
    private InteractionResult useResult(Level level, Player player, InteractionHand hand) {
    *///?} else {
    @Override public InteractionResult use(Level level, Player player, InteractionHand hand) {
    //?}
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        try {
            if (open(serverPlayer, hand)) return InteractionResult.SUCCESS;
            player.sendSystemMessage(Component.literal("This treasure bag references a missing treasure pool."));
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(TreasureBagItem.class).error("Could not open treasure bag", failure);
            player.sendSystemMessage(Component.literal("Codex could not open this bag. Check the server log before retrying."));
        }
        return InteractionResult.FAIL;
    }
}
