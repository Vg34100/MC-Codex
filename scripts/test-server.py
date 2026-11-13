#!/usr/bin/env python3
"""Isolated real dedicated servers with TWO SIMULATED PLAYERS, not connected game clients.

Run on Windows (the repo's Java/Gradle configuration): python scripts/test-server.py [fabric|neoforge|all]
Worlds/results/logs stay in runs/validation/<loader>; --reset removes only that test directory.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile
import re
import runpy
import os
import signal
import hashlib

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('loader', choices=['fabric', 'neoforge', 'all'], nargs='?', default='all')
    parser.add_argument('--minecraft-version', default='26.1.2', help='Stonecutter Minecraft target')
    parser.add_argument('--reset', action='store_true')
    parser.add_argument('--phase', choices=['all', 'initial', 'restart', 'independent'], default='all')
    parser.add_argument('--compat-dir', type=Path, help='Directory with fabric/ and neoforge/ subdirectories containing unmodified test-mod jars')
    parser.add_argument('--build-init', type=Path, help='Optional Gradle init script for isolated build outputs')
    parser.add_argument('--offline', action='store_true', help='Use cached Gradle dependencies')
    args = parser.parse_args()
    for loader in (['fabric', 'neoforge'] if args.loader == 'all' else [args.loader]):
        target = f'{args.minecraft_version}-{loader}'
        folder = ROOT / 'runs' / 'validation' / target
        if args.reset and folder.exists():
            shutil.rmtree(folder)
        for phase, world in [('initial', 'world-a'), ('restart', 'world-a'), ('independent', 'world-b')]:
            if args.phase != 'all' and phase != args.phase:
                continue
            if phase == 'initial':
                installer = runpy.run_path(str(ROOT / 'scripts' / 'install-examples.py'))['install']
                for example in ['story-demo', 'vanilla-enhanced', 'framework-showcase']:
                    installer(example, folder / 'codex' / 'advancements')
            command = [sys.executable, 'build-smart.py', f':{target}:runCodexTest',
                       '-Pcodex_tests', f'-Pcodex_test_phase={phase}', f'-Pcodex_test_world={world}']
            if args.offline:
                command += ['--offline']
            if args.build_init:
                command += ['--init-script', str(args.build_init.resolve())]
            if args.compat_dir:
                if not (args.compat_dir / loader).is_dir():
                    parser.error(f'Missing compatibility mod directory: {args.compat_dir / loader}')
                namespaces = set()
                fingerprints = []
                for jar in (args.compat_dir / loader).glob('*.jar'):
                    fingerprints.append({'file': jar.name, 'sha256': hashlib.sha256(jar.read_bytes()).hexdigest()})
                    with zipfile.ZipFile(jar) as archive:
                        namespaces.update(match.group(1) for name in archive.namelist()
                                          if (match := re.match(r'data/([^/]+)/advancement/.+\.json$', name)))
                command += [f'-Pcodex_compat_dir={args.compat_dir.resolve()}', f'-Pcodex_test_compat={",".join(sorted(namespaces))}']
                (folder / 'compatibility-mods.json').write_text(json.dumps(fingerprints, indent=2) + '\n', encoding='utf-8')
            report = folder / 'results.json'
            report.unlink(missing_ok=True)
            try:
                with subprocess.Popen(command, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                      text=True, start_new_session=os.name != 'nt') as process:
                    try:
                        output, _ = process.communicate(timeout=360)
                    except subprocess.TimeoutExpired:
                        if os.name == 'nt':
                            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
                        else:
                            os.killpg(process.pid, signal.SIGKILL)
                        process.communicate()
                        raise
                    result = subprocess.CompletedProcess(command, process.returncode, output)
            except subprocess.TimeoutExpired:
                print(f'FAIL {loader}/{phase}: server timeout (see {folder}/logs/latest.log)')
                return 1
            if not report.exists():
                print(result.stdout[-3500:])
                print(f'FAIL {loader}/{phase}: no results; see {folder}/logs/latest.log')
                return 1
            data = json.loads(report.read_text(encoding='utf-8'))
            print(f'{loader}/{phase}: {len(data["passed"])} passed (SIMULATED players)', flush=True)
            (folder / f'results-{phase}.json').write_text(json.dumps(data, indent=2) + '\n', encoding='utf-8')
            crashed = 'Encountered an unexpected exception' in (folder / 'logs' / 'latest.log').read_text(encoding='utf-8', errors='replace')
            if result.returncode or data['failures'] or crashed:
                print('\n'.join(data['failures']))
                if crashed:
                    print('Unexpected dedicated-server exception; inspect the log.')
                print(f'FAIL: see {folder}/logs/latest.log')
                return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
