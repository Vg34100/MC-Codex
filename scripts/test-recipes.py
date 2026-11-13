#!/usr/bin/env python3
"""Focused dedicated-server recipe codec/crafting checks. Uses only runs/recipe-validation."""
import argparse,json,subprocess,sys
from pathlib import Path
p=argparse.ArgumentParser(description=__doc__);p.add_argument('loader',choices=['fabric','neoforge','all'],default='all',nargs='?');p.add_argument('--minecraft-version',default='26.1.2');p.add_argument('--build-init',type=Path);a=p.parse_args()
root=Path(__file__).resolve().parents[1]
for loader in (['fabric','neoforge'] if a.loader=='all' else [a.loader]):
 target=f'{a.minecraft_version}-{loader}'
 cmd=[sys.executable,'build-smart.py',f':{target}:runCodexTest','-Pcodex_tests','-Pcodex_recipe_probe','--offline']
 if a.build_init:cmd+=['--init-script',str(a.build_init.resolve())]
 report=root/'runs/recipe-validation'/target/'results.json';report.unlink(missing_ok=True)
 with subprocess.Popen(cmd,cwd=root,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True) as proc:
  try:out,_=proc.communicate(timeout=240)
  except subprocess.TimeoutExpired:
   subprocess.run(['taskkill','/PID',str(proc.pid),'/T','/F'],capture_output=True);raise
 if not report.exists():print(out[-1800:]);raise SystemExit(1)
 d=json.loads(report.read_text());print(loader,len(d['passed']),'passed',d['failures'],flush=True)
 if proc.returncode or d['failures']:raise SystemExit(1)
