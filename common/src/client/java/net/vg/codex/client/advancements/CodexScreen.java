package net.vg.codex.client.advancements;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Changes the effective GUI coordinate system only while a Codex screen is active. */
public abstract class CodexScreen extends Screen {
    protected CodexScreen(Component title) { super(title); }

    @Override public void added() {
        super.added();
        minecraft.getWindow().setGuiScale(CodexClientSettings.effectiveScale());
    }

    @Override public void removed() {
        minecraft.getWindow().setGuiScale(CodexClientSettings.nativeScale());
        super.removed();
    }

    //? if <26.1 {
    /*public void init(int width, int height) { super.init(net.minecraft.client.Minecraft.getInstance(),width,height); }
    @Override public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
        minecraft.getWindow().setGuiScale(CodexClientSettings.effectiveScale());
        super.resize(minecraft, minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
    }
    @Override public boolean keyPressed(int key, int scan, int modifiers) { return keyPressed(new net.vg.codex.client.compat.KeyEvent(key, scan, modifiers)); }
    public boolean keyPressed(net.vg.codex.client.compat.KeyEvent event) { return super.keyPressed(event.key(), event.scan(), event.modifiers()); }
    @Override public boolean mouseClicked(double x, double y, int button) { return mouseClicked(new net.vg.codex.client.compat.MouseButtonEvent(x,y,button), false); }
    public boolean mouseClicked(net.vg.codex.client.compat.MouseButtonEvent event, boolean doubled) { return super.mouseClicked(event.x(),event.y(),event.button()); }
    @Override public boolean mouseReleased(double x, double y, int button) { return mouseReleased(new net.vg.codex.client.compat.MouseButtonEvent(x,y,button)); }
    public boolean mouseReleased(net.vg.codex.client.compat.MouseButtonEvent event) { return super.mouseReleased(event.x(),event.y(),event.button()); }
    @Override public boolean mouseDragged(double x, double y, int button, double dx, double dy) { return mouseDragged(new net.vg.codex.client.compat.MouseButtonEvent(x,y,button),dx,dy); }
    public boolean mouseDragged(net.vg.codex.client.compat.MouseButtonEvent event,double dx,double dy) { return super.mouseDragged(event.x(),event.y(),event.button(),dx,dy); }
    *///?} else {
    @Override public void resize(int width, int height) {
        minecraft.getWindow().setGuiScale(CodexClientSettings.effectiveScale());
        super.resize(minecraft.getWindow().getGuiScaledWidth(), minecraft.getWindow().getGuiScaledHeight());
    }
    //?}
    //? if <1.21 {
    /*@Override public boolean mouseScrolled(double x,double y,double amount) { return mouseScrolled(x,y,0,amount); }
    public boolean mouseScrolled(double x,double y,double horizontal,double vertical) { return super.mouseScrolled(x,y,vertical); }
    *///?}
}
