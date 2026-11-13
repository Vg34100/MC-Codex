"""Install the prepared pack as ONE NEW Prism instance. Never updates an existing instance."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,uuid,zipfile
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--prism-root',type=Path,default=Path(os.environ.get('APPDATA','C:/Users/video/AppData/Roaming'))/'PrismLauncher');args=p.parse_args()
prism=args.prism_root;name='CodexSurvival-26.1.2-Fabric';target=prism/name;stage=ROOT/'runs/survival-pack/game';source=ROOT/'modpacks/codex-survival'
assert (prism/'prismlauncher.cfg').is_file(),'Prism installation not found'
assert not target.exists(),'Instance already exists; existing worlds/settings will not be overwritten'
lock=json.loads((source/'mods.lock.json').read_text());assert lock['minecraft']=='26.1.2' and lock['loader']=='fabric' and lock['loader_version']=='0.19.5'
expected={m['file'] for m in lock['mods']};assert expected=={f.name for f in (stage/'mods').glob('*.jar')}
assert not any('mine-mcp' in f.lower() for f in expected),'Test control must never ship'
for mod in lock['mods']:assert hashlib.sha512((stage/'mods'/mod['file']).read_bytes()).hexdigest()==mod['sha512'],mod['file']
index=json.loads((source/'quests.index.json').read_text());assert len(index)==80 and len({q['id'] for q in index})==80
java=Path('C:/Users/video/.jdks/ms-25.0.3/bin/javaw.exe');assert java.is_file(),'Java25 not found'
# Copy only version components from an existing26.1.2 Fabric definition, never its game/account/settings.
meta=json.loads((prism/'DEV_26.1.2_FB/mmc-pack.json').read_text());components=meta['components']
assert next(c for c in components if c['uid']=='net.minecraft')['version']=='26.1.2'
loader=next(c for c in components if c['uid']=='net.fabricmc.fabric-loader');loader['version']='0.19.5';loader['cachedVersion']='0.19.5'
meta={'formatVersion':1,'components':components}
settings={'ConfigVersion':'1.3','InstanceType':'OneSix','name':'Codex Survival - Playtest','iconKey':'grass','OverrideMemory':'true','MinMemAlloc':'512','MaxMemAlloc':'4096','OverrideJavaLocation':'true','AutomaticJava':'false','JavaPath':java.as_posix(),'JavaVersion':'25.0.3','JavaArchitecture':'64','JavaRealArchitecture':'amd64','OverrideWindow':'true','MinecraftWinWidth':'1280','MinecraftWinHeight':'720','LaunchMaximized':'false','ManagedPack':'false','GlobalDataPacksEnabled':'false','UseAccountForInstance':'false','uuid':uuid.uuid4().hex,'notes':'Fabric26.1.2 playtest.80 quests. Create a NEW Survival world. No test worlds or test-control mods included. See minecraft/PLAYTEST.md.'}
# Validate completely before writing outside the workspace. Register only after every file is copied.
temporary=prism/(name+'.installing-'+uuid.uuid4().hex[:8]);temporary.mkdir()
try:
 game=temporary/'minecraft';game.mkdir();shutil.copytree(stage/'mods',game/'mods');shutil.copytree(source/'overrides',game,dirs_exist_ok=True)
 shutil.copy2(source/'QUESTS.md',game/'QUESTS.md');shutil.copy2(source/'README.md',game/'PLAYTEST.md');shutil.copy2(source/'mods.lock.json',game/'mods.lock.json')
 (temporary/'mmc-pack.json').write_text(json.dumps(meta,indent=2)+'\n',encoding='utf8')
 (temporary/'instance.cfg').write_text('[General]\n'+'\n'.join(k+'='+v for k,v in settings.items())+'\n',encoding='utf8')
 assert not (game/'saves').exists() and not any('mcp' in f.name.lower() for f in (game/'mods').iterdir())
 for m in lock['mods']:assert hashlib.sha512((game/'mods'/m['file']).read_bytes()).hexdigest()==m['sha512']
 temporary.rename(target)
except Exception:
 # This directory was created by this invocation and can contain no pre-existing player data.
 shutil.rmtree(temporary,ignore_errors=True);raise
receipt={'instance':str(target),'name':settings['name'],'minecraft':'26.1.2','loader':'Fabric0.19.5','mods':len(lock['mods']),'quests':80,'player_worlds_included':False,'test_control_included':False}
(ROOT/'runs/survival-pack/prism-installation.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(json.dumps(receipt,indent=2))
# Portable private playtest import; never uploaded or published by this tool.
archive=ROOT/'runs/survival-pack/CodexSurvival-26.1.2-Fabric-prism.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as out:
 for f in target.rglob('*'):
  if f.is_file():out.write(f,f.relative_to(target))
print('Private import archive:',archive,flush=True)
