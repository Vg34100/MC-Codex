"""Resolve exact Fabric26.1.2 artifacts; download only to the disposable staging directory."""
import hashlib,json,shutil,urllib.request,urllib.parse,zipfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];DEST=ROOT/'runs/survival-pack/game/mods';DEST.mkdir(parents=True,exist_ok=True)
HEAD={'User-Agent':'MC-Codex-SurvivalPlaytest/0.1 (local modpack authoring)'}
lock=[];seen=set()
cache=ROOT/'runs/survival-pack/download-metadata';cache.mkdir(exist_ok=True)
def get(url):
 cached=cache/(hashlib.sha256(url.encode()).hexdigest()+'.json')
 if ('api.github.com/' in url or 'api.modrinth.com/' in url) and cached.exists():return cached.read_bytes()
 for attempt in range(3):
  try:
   with urllib.request.urlopen(urllib.request.Request(url,headers=HEAD),timeout=45) as r:data=r.read()
   if 'api.github.com/' in url or 'api.modrinth.com/' in url:cached.write_bytes(data)
   return data
  except OSError:
   if attempt==2:raise
   time.sleep(1)
def api(path):return json.loads(get('https://api.modrinth.com/v2/'+path))
def fetch(url,filename,hashes,source,version):
 p=DEST/filename
 algorithm='sha512' if 'sha512' in hashes else 'sha256'
 expected=hashes.get(algorithm)
 if not p.exists() or (expected and hashlib.new(algorithm,p.read_bytes()).hexdigest()!=expected):
  data=get(url)
  if expected:assert hashlib.new(algorithm,data).hexdigest()==expected,filename
  p.write_bytes(data)
 with zipfile.ZipFile(p) as z:
  meta=json.loads(z.read('fabric.mod.json')) if 'fabric.mod.json' in z.namelist() else None
 if meta:
  requirement=meta.get('depends',{}).get('minecraft','*')
  print(meta['id'],meta['version'],'minecraft',requirement,flush=True)
 lock.append({'file':filename,'sha512':hashlib.sha512(p.read_bytes()).hexdigest(),'source':source,'url':url,'version':version,'metadata':meta})
def resolve(project=None,version_id=None):
 if version_id:v=api('version/'+version_id)
 else:
  versions=api('project/'+project+'/version?'+urllib.parse.urlencode({'loaders':json.dumps(['fabric']),'game_versions':json.dumps(['26.1.2'])}))
  if not versions:raise RuntimeError('No Fabric26.1.2 artifact for '+project)
  versions.sort(key=lambda v:(v['version_type']=='release',v['date_published']),reverse=True);v=versions[0]
 if v['project_id'] in seen:return
 assert '26.1.2' in v['game_versions'] and 'fabric' in v['loaders'],v['name']
 seen.add(v['project_id'])
 f=next((f for f in v['files'] if f.get('primary')),v['files'][0]);fetch(f['url'],f['filename'],f['hashes'],'modrinth:'+v['project_id'],v['version_number'])
 for d in v['dependencies']:
  if d['dependency_type']=='required':resolve(d.get('project_id'),d.get('version_id'))
for project in ['endrem','farmers-delight-refabricated','dungeons-and-taverns','jei','jade','sodium','lithium','ferrite-core','modmenu','appleskin','cloth-config']:
 resolve(project)
# Reuse previously verified GitHub artifacts; fetch release metadata only on first resolution.
def github(repository,tag,version):
 previous_path=ROOT/'modpacks/codex-survival/mods.lock.json'
 previous=json.loads(previous_path.read_text())['mods'] if previous_path.exists() else []
 prior=next((m for m in previous if m['source']=='github:'+repository and m['version']==version),None)
 if prior:
  fetch(prior['url'],prior['file'],{'sha512':prior['sha512']},prior['source'],version);return
 release=json.loads(get('https://api.github.com/repos/'+repository+'/releases/tags/'+urllib.parse.quote(tag,safe='')))
 a=next(a for a in release['assets'] if 'fabric' in a['name'].lower() and a['name'].endswith('.jar'))
 digest=a.get('digest') or '';hashes={'sha256':digest.split(':',1)[1]} if digest.startswith('sha256:') else {}
 fetch(a['browser_download_url'],a['name'],hashes,'github:'+repository,version)
github('MrCrayfish/Backpacked','v3.0.4+26.1.2','3.0.4+26.1.2')
github('MrCrayfish/Framework','v0.13.23+26.1.2','0.13.23+26.1.2')
resolve('architectury-api')
# Existing local distribution artifacts; never select raw/source/dev or another game version.
for mod,pattern in [('codex','build/libs/26.1.2-fabric/codex-fabric-26.1.2-0.1.0.jar'),('spelunkery','../spelunkery/build/publishing/github-assets/spelunkery-fabric-26.1.2-1.0.2.jar'),('fishingfrenzy','../fishingfrenzy/build/publishing/github-assets/fishingfrenzy-fabric-26.1.2-1.2.3.jar'),('combo','../combo/fabric/build/libs/combo-fabric-26.1.2-0.1.0.jar'),('sagittary','../sagittary/build/publishing/github-assets/sagittary-fabric-26.1.2-2.0.1.jar')]:
 p=(ROOT/pattern).resolve();assert p.is_file(),p
 with zipfile.ZipFile(p) as z:meta=json.loads(z.read('fabric.mod.json'));assert meta['id']==mod
 shutil.copy2(p,DEST/p.name);lock.append({'file':p.name,'sha512':hashlib.sha512(p.read_bytes()).hexdigest(),'source':'local:'+mod,'version':meta['version'],'metadata':meta})
(ROOT/'modpacks/codex-survival/mods.lock.json').write_text(json.dumps({'minecraft':'26.1.2','loader':'fabric','loader_version':'0.19.5','mods':lock},indent=2)+'\n')
print('LOCKED',len(lock),'artifacts',flush=True)
