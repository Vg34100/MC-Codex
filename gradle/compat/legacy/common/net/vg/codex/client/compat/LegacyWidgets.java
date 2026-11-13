package net.vg.codex.client.compat;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;
import java.util.function.Function;
public final class LegacyWidgets {
 private LegacyWidgets(){}
 public static <T> CycleButton.Builder<T> cycle(Function<T,Component> name,T initial){return CycleButton.<T>builder(name).withInitialValue(initial);}
 public static MultilineBuilder multiline(){return new MultilineBuilder();}
 public static final class MultilineBuilder {
  private int x,y;private Component placeholder=Component.empty();
  public MultilineBuilder setX(int value){x=value;return this;} public MultilineBuilder setY(int value){y=value;return this;}
  public MultilineBuilder setPlaceholder(Component value){placeholder=value;return this;}
  public MultiLineEditBox build(Font font,int width,int height,Component message){return new MultiLineEditBox(font,x,y,width,height,placeholder,message);}
 }
}
