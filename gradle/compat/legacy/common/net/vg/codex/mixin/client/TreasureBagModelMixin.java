package net.vg.codex.mixin.client;

import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.vg.codex.advancement.TreasureBagItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Uses a selected native item model on versions before the item_model component. */
@Mixin(ItemRenderer.class)
abstract class TreasureBagModelMixin {
    @Inject(method="getModel",at=@At("HEAD"),cancellable=true)
    private void codex$bagModel(ItemStack stack,Level level,LivingEntity entity,int seed,CallbackInfoReturnable<BakedModel> ci) {
        if(TreasureBagItem.ITEM==null || !stack.is(TreasureBagItem.ITEM))return;
        CompoundTag data;
        //? if <1.21 {
        /*data=stack.getTag();
        *///?} else {
        data=stack.getOrDefault(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.EMPTY).copyTag();
        //?}
        if(data==null)return;
        ResourceLocation id=ResourceLocation.tryParse(data.getString("codex_appearance"));
        if(id==null || !BuiltInRegistries.ITEM.containsKey(id))return;
        var item=BuiltInRegistries.ITEM.get(id);
        if(item==TreasureBagItem.ITEM)return;
        BakedModel model=((ItemRenderer)(Object)this).getModel(item.getDefaultInstance(),level,entity,seed);
        // Entity-backed renderers need their real item state; retain the standard bag for them.
        if(!model.isCustomRenderer())ci.setReturnValue(model);
    }
}
