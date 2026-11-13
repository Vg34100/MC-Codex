"""Prepare reproducible local playtest files; the installer separately creates a new Prism instance."""
from pathlib import Path
import json,shutil,hashlib
ROOT=Path(__file__).resolve().parents[2];SOURCE=ROOT/'modpacks/codex-survival';GAME=ROOT/'runs/survival-pack/game'
lock=json.loads((SOURCE/'mods.lock.json').read_text())
expected={m['file'] for m in lock['mods']}
assert {p.name for p in (GAME/'mods').glob('*.jar')}==expected,'Unexpected mod in staging'
for m in lock['mods']:assert hashlib.sha512((GAME/'mods'/m['file']).read_bytes()).hexdigest()==m['sha512'],m['file']
shutil.copytree(SOURCE/'overrides',GAME,dirs_exist_ok=True)
shutil.copy2(SOURCE/'QUESTS.md',GAME/'QUESTS.md')
shutil.copy2(SOURCE/'mods.lock.json',GAME/'mods.lock.json')
print('Staged',len(expected),'mod JARs;80 authored quests',flush=True)
