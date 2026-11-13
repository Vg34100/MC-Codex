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
 //? if <1.21 {
 /*public static CheckBuilder checkbox(Component label,Font font){return new CheckBuilder(label,font);}
 public static final class CheckBuilder {
  private final Component label;private final Font font;private int x,y,width=240;private boolean selected;
  CheckBuilder(Component label,Font font){this.label=label;this.font=font;}
  public CheckBuilder pos(int a,int b){x=a;y=b;return this;}
  public CheckBuilder maxWidth(int value){width=Math.max(20,value);return this;}
  public CheckBuilder selected(boolean value){selected=value;return this;}
  public Checkbox build(){String text=font.plainSubstrByWidth(label.getString(),Math.max(0,width-24));Checkbox box=new Checkbox(x,y,Math.min(width,font.width(text)+24),20,Component.literal(text),selected);box.setTooltip(Tooltip.create(label));return box;}
 }
 *///?}
}
