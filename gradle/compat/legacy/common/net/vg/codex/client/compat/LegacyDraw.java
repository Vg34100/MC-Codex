package net.vg.codex.client.compat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.mojang.blaze3d.vertex.PoseStack;
/** Native drawing and 2D-to-3D pose adapters; all pixels still use Minecraft renderers. */
public final class LegacyDraw {
 private LegacyDraw(){}
 public record Pose(PoseStack stack) {
  public void pushMatrix(){stack.pushPose();} public void popMatrix(){stack.popPose();}
  public void translate(float x,float y){stack.translate(x,y,0);}
  public void scale(float v){stack.scale(v,v,1);} public void scale(float x,float y){stack.scale(x,y,1);}
 }
 public static Pose pose(GuiGraphics nativeGraphics){return new Pose(nativeGraphics.pose());}
 public static void blit(GuiGraphics nativeGraphics,Object pipeline,ResourceLocation texture,int x,int y,float u,float v,int w,int h,int tw,int th){nativeGraphics.blit(texture,x,y,u,v,w,h,tw,th);}
 public static void blitSprite(GuiGraphics nativeGraphics,Object pipeline,ResourceLocation sprite,int x,int y,int w,int h){
  //? if <1.21 {
  /*net.vg.codex.compat.v120.ClassicSprites120.draw(nativeGraphics,sprite,x,y,w,h);
  *///?} else {
  nativeGraphics.blitSprite(sprite,x,y,w,h);
  //?}
 }
 public static void blitSprite(GuiGraphics nativeGraphics,Object pipeline,ResourceLocation sprite,int x,int y,int w,int h,int color){
  nativeGraphics.setColor((color>>16&255)/255F,(color>>8&255)/255F,(color&255)/255F,(color>>>24)/255F);
  try{
  //? if <1.21 {
  /*net.vg.codex.compat.v120.ClassicSprites120.draw(nativeGraphics,sprite,x,y,w,h);
  *///?} else {
  nativeGraphics.blitSprite(sprite,x,y,w,h);
  //?}
 }finally{nativeGraphics.setColor(1,1,1,1);}
 }
 public static void blitSprite(GuiGraphics nativeGraphics,Object pipeline,ResourceLocation sprite,int sw,int sh,int u,int v,int x,int y,int w,int h){
  //? if <1.21 {
  /*net.vg.codex.compat.v120.ClassicSprites120.crop(nativeGraphics,sprite,sw,sh,u,v,x,y,w,h);
  *///?} else {
  nativeGraphics.blitSprite(sprite,sw,sh,u,v,x,y,w,h);
  //?}
 }
 public static void background(GuiGraphics g){var mc=net.minecraft.client.Minecraft.getInstance();g.fillGradient(0,0,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight(),0xC0101010,0xD0101010);}
}
