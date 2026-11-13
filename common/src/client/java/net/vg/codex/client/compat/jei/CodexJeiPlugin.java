package net.vg.codex.client.compat.jei;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.gui.handlers.IGlobalGuiHandler;
import mezz.jei.api.gui.handlers.IGuiProperties;
//? if >=1.21.1 {
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
//?}
import mezz.jei.api.runtime.IClickableIngredient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.vg.codex.client.advancements.CodexAdvancementsScreen;
import java.util.Optional;

/** Discovered by JEI only; Codex has no required runtime dependency on JEI. */
@JeiPlugin
public final class CodexJeiPlugin implements IModPlugin {
    @Override public Identifier getPluginUid() { return Identifier.fromNamespaceAndPath("codex", "hover_items"); }
    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // JEI needs a recognized screen region before it dispatches recipe keys to global handlers.
        registration.addGuiScreenHandler(CodexAdvancementsScreen.class, screen -> new IGuiProperties() {
            @Override public Class<? extends net.minecraft.client.gui.screens.Screen> screenClass() { return CodexAdvancementsScreen.class; }
            @Override public int guiLeft() { return 0; }
            @Override public int guiTop() { return 0; }
            @Override public int guiXSize() { return screen.width; }
            @Override public int guiYSize() { return screen.height; }
            @Override public int screenWidth() { return screen.width; }
            @Override public int screenHeight() { return screen.height; }
        });
        registration.addGlobalGuiHandler(new IGlobalGuiHandler() {
            //? if <1.21.1 {
            /*@Override public Optional<IClickableIngredient<?>> getClickableIngredientUnderMouse(double mouseX, double mouseY) {
                if (Minecraft.getInstance().screen instanceof CodexAdvancementsScreen screen) {
                    var hit = screen.hoverIngredientAt(mouseX, mouseY);
                    if (hit != null && !hit.item().isEmpty()) return registration.getJeiHelpers().getIngredientManager()
                            .createTypedIngredient(mezz.jei.api.constants.VanillaTypes.ITEM_STACK,hit.item())
                            .map(typed -> new IClickableIngredient<net.minecraft.world.item.ItemStack>() {
                                public mezz.jei.api.ingredients.ITypedIngredient<net.minecraft.world.item.ItemStack> getTypedIngredient() { return typed; }
                                public net.minecraft.client.renderer.Rect2i getArea() { return new net.minecraft.client.renderer.Rect2i(hit.x(),hit.y(),16,16); }
                            });
                }
                return Optional.empty();
            }
            *///?} else {
            @Override public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(IClickableIngredientFactory factory, double mouseX, double mouseY) {
                if (Minecraft.getInstance().screen instanceof CodexAdvancementsScreen screen) {
                    var hit = screen.hoverIngredientAt(mouseX, mouseY);
                    if (hit != null && !hit.item().isEmpty()) return factory.createBuilder(hit.item()).buildWithArea(hit.x(), hit.y(), 16, 16);
                }
                return Optional.empty();
            }
            //?}
        });
    }
}
