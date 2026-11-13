//? if <1.21 {
/*package net.vg.codex.validation;
public final class CraftingInput extends net.minecraft.world.inventory.TransientCraftingContainer {
 private CraftingInput(int width,int height){super(new net.minecraft.world.inventory.AbstractContainerMenu(null,0){public boolean stillValid(net.minecraft.world.entity.player.Player player){return true;}public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player,int slot){return net.minecraft.world.item.ItemStack.EMPTY;}},width,height);}
 public static CraftingInput of(int width,int height,java.util.List<net.minecraft.world.item.ItemStack> stacks){CraftingInput result=new CraftingInput(width,height);for(int i=0;i<stacks.size();i++)result.setItem(i,stacks.get(i));return result;}
}

*///?}
