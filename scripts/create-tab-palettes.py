#!/usr/bin/env python3
"""Apply the completed-advancement gold and darker gray palettes to vanilla GUI border pixels.

Usage: python scripts/create-tab-palettes.py --client-jar <26.1.2 client jar>
Requires Pillow. Original transparency, pixel geometry, highlights and shadows are retained.
"""
import argparse,io,zipfile
from pathlib import Path
from PIL import Image
ROOT=Path(__file__).resolve().parents[1]
PALETTES={'quest':{85:(74,74,74),139:(121,121,121),198:(174,174,174),255:(225,225,225)},'gold':{85:(73,54,6),139:(143,106,13),198:(170,126,15),255:(219,162,19)}}
def recolor(image,palette):
 image=image.convert('RGBA');pixels=[]
 for r,g,b,a in image.getdata():
  color=palette.get(r) if r==g==b and a else None
  pixels.append((*color,a) if color else (r,g,b,a))
 image.putdata(pixels);return image
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--client-jar',required=True,type=Path);args=p.parse_args()
 with zipfile.ZipFile(args.client_jar) as jar:
  names=['assets/minecraft/textures/gui/advancements/window.png']+[f'assets/minecraft/textures/gui/sprites/advancements/tab_above_{edge}{selected}.png' for edge in ['left','middle','right'] for selected in ['','_selected']]
  for name in names:
   source=Image.open(io.BytesIO(jar.read(name)))
   for style,palette in PALETTES.items():
    relative=name.replace('assets/minecraft/','assets/codex/').replace('.png','_'+style+'.png');target=ROOT/'common/src/main/resources'/relative;target.parent.mkdir(parents=True,exist_ok=True);recolor(source,palette).save(target)
 print('Authored14 palette variants from vanilla GUI borders.')
