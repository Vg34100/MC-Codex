package net.vg.codex.client.advancements;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.world.item.ItemStack;
import net.vg.codex.advancement.SubmissionPlan;
import java.util.function.Consumer;

/** Ordinary property matching is visual; complex component predicates retain an escape hatch. */
public final class DeliveryPropertiesScreen extends CodexScreen {
    private final Screen parent; private final JsonObject row; private final Consumer<JsonObject> apply;
    private String error = ""; private int left, size;
    DeliveryPropertiesScreen(Screen parent, JsonObject source, Consumer<JsonObject> apply) {
        super(Component.literal("Delivery Item Properties")); this.parent=parent; this.row=source.deepCopy(); this.apply=apply;
    }
    private JsonObject properties() { if(!row.has("components")) row.add("components",new JsonObject()); return row.getAsJsonObject("components"); }
    @Override protected void init() {
        size=Math.min(460,width-24); left=(width-size)/2; JsonObject p=properties();
        EditBox name=addRenderableWidget(new EditBox(font,left,56,size,20,Component.literal("Exact custom name (optional)"))); name.setMaxLength(80);
        if(p.has("minecraft:custom_name")) name.setValue(net.vg.codex.advancement.ItemFormats.displayName(p.get("minecraft:custom_name")));
        name.setHint(Component.literal("Any name")); name.setResponder(value->{if(value.isEmpty())p.remove("minecraft:custom_name");else {JsonObject text=new JsonObject();text.addProperty("text",value);p.add("minecraft:custom_name",text);}});
        addRenderableWidget(Button.builder(Component.literal(p.has("minecraft:potion_contents") ? "Potion: " + (p.getAsJsonObject("minecraft:potion_contents").has("potion") ? ClientRewardPresentation.pretty(Identifier.parse(p.getAsJsonObject("minecraft:potion_contents").get("potion").getAsString())) : "Custom properties") : "Match potion type…"),b->minecraft.setScreen(new IdentifierSelectionScreen(this,"Potion Type",BuiltInRegistries.POTION.keySet(),id->Component.literal(ClientRewardPresentation.pretty(id)),id->{JsonObject potion=new JsonObject();potion.addProperty("potion",id.toString());p.add("minecraft:potion_contents",potion);}))).bounds(left,84,size-64,20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"),b->{p.remove("minecraft:potion_contents");rebuildWidgets();}).bounds(left+size-60,84,60,20).build());
        EditBox damage=addRenderableWidget(new EditBox(font,left,126,size,20,Component.literal("Exact damage (optional)"))); damage.setMaxLength(8); damage.setHint(Component.literal("Any durability"));
        if(p.has("minecraft:damage")) damage.setValue(p.get("minecraft:damage").getAsString());
        damage.setResponder(value->{try{if(value.isBlank())p.remove("minecraft:damage");else{int amount=Integer.parseInt(value);if(amount<0)throw new NumberFormatException();p.addProperty("minecraft:damage",amount);}error="";}catch(NumberFormatException invalid){error="Damage must be a nonnegative integer";}});
        addRenderableWidget(Button.builder(Component.literal("Copy Properties from Held Item"),b->{
            try { ItemStack stack=minecraft.player.getMainHandItem();if(stack.isEmpty())throw new IllegalArgumentException("Hold an example item first");
                //? if <1.21 {
                /*row.add("components",net.vg.codex.compat.v120.LegacyItems.heldProperties(stack));
                *///?} else {
                JsonObject encoded=ItemStack.CODEC.encodeStart(RegistryOps.create(JsonOps.INSTANCE,minecraft.level.registryAccess()),stack).getOrThrow().getAsJsonObject();
                row.add("components",encoded.has("components")?encoded.get("components").deepCopy():new JsonObject());
                //?}
                error="";rebuildWidgets();
            } catch(RuntimeException invalid){error=invalid.getMessage();}
        }).bounds(left,154,size,20).build()).setTooltip(Tooltip.create(Component.literal("Copies explicit component values, including enchantments and custom data. Does not consume the example item.")));
        addRenderableWidget(Button.builder(Component.literal("Advanced Properties…"),b->minecraft.setScreen(new JsonDocumentScreen(this,properties(),value->{row.add("components",value);})) ).bounds(left,180,size/2-3,20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear All Properties"),b->{row.remove("components");rebuildWidgets();}).bounds(left+size/2+3,180,size/2-3,20).build());
        addRenderableWidget(Button.builder(Component.literal("Apply"),b->{
            try {if(!error.isEmpty())return;JsonObject task=new JsonObject();JsonArray items=new JsonArray();items.add(row);task.add("items",items);SubmissionPlan.resolve(task,minecraft.level.registryAccess());apply.accept(row.deepCopy());minecraft.setScreen(parent);}catch(RuntimeException invalid){error=invalid.getMessage();}
        }).bounds(left,height-32,size/2-3,20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"),b->onClose()).bounds(left+size/2+3,height-32,size/2-3,20).build());
    }
    @Override public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float dt){g.fill(0,0,width,height,0xED202020);g.text(font,title,left,14,-1,false);g.text(font,"Exact custom name",left,42,0xFFCCCCAA,false);g.text(font,"Exact damage / durability used",left,112,0xFFCCCCAA,false);super.extractRenderState(g,x,y,dt);if(!error.isEmpty())g.text(font,font.plainSubstrByWidth(error,size),left,30,0xFFFF7777,false);}
    @Override public void onClose(){minecraft.setScreen(parent);}
}
