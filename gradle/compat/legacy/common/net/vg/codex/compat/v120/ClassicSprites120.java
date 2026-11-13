//? if <1.21 {
/*package net.vg.codex.compat.v120;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.advancements.FrameType;
public final class ClassicSprites120 {
 private static final ResourceLocation WIDGETS=new ResourceLocation("textures/gui/advancements/widgets.png");
 private static final ResourceLocation TABS=new ResourceLocation("textures/gui/advancements/tabs.png");
 private ClassicSprites120(){}
 public static void draw(GuiGraphics g,ResourceLocation sprite,int x,int y,int width,int height){
  String path=sprite.getPath();
  if(sprite.getNamespace().equals("codex")){g.blit(new ResourceLocation("codex","textures/gui/sprites/"+path+".png"),x,y,0,0,width,height,width,height);return;}
  if(path.contains("_frame_")){
   String frame=path.substring(path.lastIndexOf('/')+1,path.indexOf("_frame_"));int index=path.endsWith("unobtained")?1:0;
   g.blit(WIDGETS,x,y,FrameType.byName(frame).getTexture(),128+index*26,width,height);return;
  }
  if(path.contains("box_")){g.blitNineSliced(WIDGETS,x,y,width,height,10,200,26,0,path.endsWith("unobtained")?26:0);return;}
  if(path.equals("advancements/title_box")){g.blitNineSliced(WIDGETS,x,y,width,height,10,200,26,0,52);return;}
  if(path.startsWith("advancements/tab_above_")){
   int u=path.contains("left")?0:path.contains("right")?56:28;int v=path.endsWith("_selected")?32:0;
   g.blit(TABS,x,y,u,v,width,height);return;
  }
  throw new IllegalArgumentException("No classic advancement sprite mapping: "+sprite);
 }
 public static void crop(GuiGraphics g,ResourceLocation sprite,int sw,int sh,int u,int v,int x,int y,int w,int h){
  if(sprite.getPath().contains("box_")){g.blit(WIDGETS,x,y,u,(sprite.getPath().endsWith("unobtained")?26:0)+v,w,h);return;}
  g.blit(new ResourceLocation(sprite.getNamespace(),"textures/gui/sprites/"+sprite.getPath()+".png"),x,y,u,v,w,h,sw,sh);
 }
}

*///?}
