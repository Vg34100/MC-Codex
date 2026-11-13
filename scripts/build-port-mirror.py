#!/usr/bin/env python3
"""Copy maintained sources to an owned disposable Windows build mirror, then run a smart build command.
Generated outputs, Git data and player worlds are never copied from the workspace.
"""
from pathlib import Path
import os,shutil,subprocess,sys,tempfile
ROOT=Path(__file__).resolve().parents[1]
MIRROR=Path(tempfile.gettempdir())/"codex-ports-20261108"
if os.name!="nt":raise SystemExit("Run with Windows Python")
MIRROR.mkdir(exist_ok=True)
(MIRROR/".codex-owned-build-mirror").write_text(str(ROOT))
roots=["common","fabric","neoforge","forge","gradle","tests","scripts","tools","examples"]
ignore=shutil.ignore_patterns("build","run","runs",".gradle","__pycache__")
for name in roots:
 source=ROOT/name
 if source.is_dir():shutil.copytree(source,MIRROR/name,dirs_exist_ok=True,ignore=ignore)
for source in ROOT.iterdir():
 if source.is_file() and (source.suffix in {".gradle",".properties",".py"} or source.name in {"gradlew","gradlew.bat","LICENSE","LICENSE.txt"}):shutil.copy2(source,MIRROR/source.name)
print("Owned build mirror:",MIRROR,flush=True)
if len(sys.argv)>1:
 if sys.argv[1]=='--run-script':
  if len(sys.argv)<3:raise SystemExit('Supply a script filename from scripts/')
  script=Path(sys.argv[2])
  if script.name!=str(script) or not (MIRROR/'scripts'/script).is_file():raise SystemExit('Expected a maintained script filename')
  raise SystemExit(subprocess.call([sys.executable,str(Path('scripts')/script),*sys.argv[3:]],cwd=MIRROR))
 raise SystemExit(subprocess.call([sys.executable,"build-smart.py",*sys.argv[1:]],cwd=MIRROR))
