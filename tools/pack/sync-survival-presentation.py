"""Merge changed quest wording/tab styles into a playtest copy without replacing author edits.

Usage: python tools/pack/sync-survival-presentation.py --previous runs/survival-pack/copy-before.json --game-dir <minecraft>
The previous snapshot maps override-relative JSON paths to their previous contents.
Only matching old descriptions/dialogue are replaced; conflicting player edits are skipped.
Player saves, criteria, recipes, rewards and progress are never written.
"""
import argparse,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def merge(current,old,new,path=()):
 changes=0
 for key,value in new.items():
  location=path+(key,)
  if isinstance(value,dict) and isinstance(old.get(key),dict) and isinstance(current.get(key),dict):
   changes+=merge(current[key],old[key],value,location)
  elif value!=old.get(key) and (key=='quest_tab' or location[-2:]==('display','description') or 'dialogue' in location):
   if current.get(key)==old.get(key):current[key]=value;changes+=1
 return changes
if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--previous',required=True,type=Path);parser.add_argument('--game-dir',required=True,type=Path);args=parser.parse_args()
 previous=json.loads(args.previous.read_text(encoding='utf8'));count=0
 for relative,old in previous.items():
  source=ROOT/'modpacks/codex-survival/overrides'/relative;target=args.game_dir/relative
  if not source.exists() or not target.exists():continue
  raw=target.read_bytes();current=json.loads(raw);new=json.loads(source.read_text(encoding='utf8'))
  if not merge(current,old,new):continue
  backup=ROOT/'runs/survival-pack/presentation-backups'/args.game_dir.parent.name/relative
  backup.parent.mkdir(parents=True,exist_ok=True)
  if not backup.exists():backup.write_bytes(raw)
  assert target.read_bytes()==raw,'Quest changed during patch; close the editor and retry'
  tmp=target.with_suffix('.json.presentation-tmp');tmp.write_text(json.dumps(current,indent=2,ensure_ascii=False)+'\n',encoding='utf8');tmp.replace(target);count+=1
 print('Updated presentation in',count,'files; existing author edits preserved.')
