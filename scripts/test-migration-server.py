#!/usr/bin/env python3
"""Cold-restart probe on CLONED CLOSED test worlds; source/user worlds are never migrated.
Run on Windows after test-clients.py completed its GUI migration export.
"""
import argparse
import importlib.util
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('migration',ROOT/'scripts/migrate-advancement.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('loader',choices=['fabric','neoforge'])
    parser.add_argument('--minecraft-version', default='26.1.2')
    parser.add_argument('--build-init',type=Path)
    parser.add_argument('--offline',action='store_true')
    args=parser.parse_args()
    target=f'{args.minecraft_version}-{args.loader}'
    source=ROOT/'runs/transport'/target/'server'
    exports=sorted((source.parent/'alice/codex/migration-plans').glob('codex_test_reward_*.json'),key=lambda p:p.stat().st_mtime)
    if not exports:
        parser.error('No GUI-exported reward migration plan exists; finish real-client GUI validation first')
    for phase in ('initial','restart'):
        result=json.loads((source.parent/f'results-{phase}.json').read_text())
        if result['failures']:
            parser.error('Source test profile did not finish successfully')
    target=ROOT/'runs/migration-validation'/target/'server'
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    worlds=[target/'world-a',target/'world-b']
    sources=[source/'world-a',ROOT/'runs/validation'/target/'world-b']
    with m.WorldLocks(sources):
        shutil.copytree(source/'codex',target/'codex')
        for old,new in zip(sources,worlds):
            shutil.copytree(old,new,ignore=shutil.ignore_patterns('session.lock'))
    plan=json.loads(exports[-1].read_text());plan['criterion_from']='done';plan['criterion_to']='done_migrated'
    pack=target/'codex/advancements'
    with m.WorldLocks(worlds):
        changes,report=m.plan_changes(plan,pack,worlds)
        m.apply_changes(changes,pack,worlds)
        (target.parent/'migration-preview.json').write_text(json.dumps(report,indent=2)+'\n')
    flags=['-Pcodex_tests','-Pcodex_migration_probe',f'-Pcodex_migration_target={plan["target"]}',f'-Pcodex_compat_dir={ROOT / "runs/compat-mods"}']
    if args.offline:
        flags+=['--offline']
    if args.build_init:
        flags+=['--init-script',str(args.build_init.resolve())]
    for world in ('world-a','world-b'):
        result_file=target/'results.json';result_file.unlink(missing_ok=True)
        with subprocess.Popen([sys.executable,'build-smart.py',f':{target}:runCodexTest',*flags,f'-Pcodex_test_world={world}'],cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True) as process:
            try:
                output,_=process.communicate(timeout=360)
            except subprocess.TimeoutExpired:
                subprocess.run(['taskkill','/PID',str(process.pid),'/T','/F'],capture_output=True)
                process.communicate();print('Migration probe timed out; owned process tree terminated');return 1
            result=subprocess.CompletedProcess(process.args,process.returncode,output)
        if not result_file.exists():
            print(result.stdout[-2000:]);return 1
        data=json.loads(result_file.read_text());(target.parent/f'results-{world}.json').write_text(json.dumps(data,indent=2)+'\n')
        print(f'{args.loader}/{world}: {len(data["passed"])} migration restart checks, {len(data["failures"])} failed',flush=True)
        if result.returncode or data['failures']:
            print('\n'.join(data['failures']));return 1
    return 0


if __name__=='__main__':
    raise SystemExit(main())
