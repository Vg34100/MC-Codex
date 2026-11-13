"""Change only notification flags in existing pack definitions; never rewrites player progress."""
from pathlib import Path
import json,shutil,os,tempfile
ROOT=Path(__file__).resolve().parents[2]
roots=[ROOT/'modpacks/codex-survival/overrides/codex/advancements',Path(os.environ.get('APPDATA','C:/Users/video/AppData/Roaming'))/'PrismLauncher/CodexSurvival-26.1.2-Fabric/minecraft/codex/advancements']
for root in roots:
 changed=0
 for file in (root/'data/codex_survival/advancement').rglob('*.json'):
  original=file.read_bytes();doc=json.loads(original)
  if 'display' not in doc or not doc.get('parent'):continue
  criteria=doc.get('criteria',{})
  if not any(c.get('trigger')!='minecraft:impossible' for c in criteria.values()):continue
  if doc['display'].get('show_toast',True):continue
  doc['display']['show_toast']=True
  backup=file.with_suffix('.json.notification-backup');
  if not backup.exists():shutil.copy2(file,backup)
  assert file.read_bytes()==original,'Definition changed during patch; retry after closing the editor'
  temporary=file.with_suffix('.json.notification-tmp');temporary.write_text(json.dumps(doc,indent=2,ensure_ascii=False)+'\n',encoding='utf8');temporary.replace(file);changed+=1
 print(root,changed,'notification flags enabled',flush=True)
