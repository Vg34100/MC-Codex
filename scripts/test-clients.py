#!/usr/bin/env python3
"""Two actual rendered Minecraft clients over loopback TCP; no OS input automation.

Windows/Java25: python scripts/test-clients.py fabric --reset --build-init runs/frame-mask-build.gradle
Uses only runs/transport/<loader>, port25577, owned child JVMs and test-only source sets.
It opens two game windows. Warn the desktop user before invoking this command.
"""
import argparse
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def terminate(process):
    if process.poll() is None:
        if sys.platform == 'win32':
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True)
        else:
            process.terminate()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('loader', choices=['fabric', 'neoforge'])
    parser.add_argument('--reset', action='store_true')
    parser.add_argument('--minecraft-version', default='26.1.2')
    parser.add_argument('--build-init', type=Path)
    parser.add_argument('--compat-dir', type=Path)
    parser.add_argument('--offline', action='store_true', help='Use cached Gradle dependencies and Minecraft assets')
    parser.add_argument('--phase', choices=['all', 'initial', 'restart'], default='all')
    args = parser.parse_args()
    target = f'{args.minecraft_version}-{args.loader}'
    folder = ROOT / 'runs' / 'transport' / target
    if args.reset and folder.exists():
        shutil.rmtree(folder)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'owned-test-profile.json').write_text('{"purpose":"Codex real-client transport validation"}\n')
    flags = ['-Pcodex_tests', '-Pcodex_transport']
    if args.offline:
        flags += ['--offline']
    if args.build_init:
        flags += ['--init-script', str(args.build_init.resolve())]
    if args.compat_dir:
        flags += [f'-Pcodex_compat_dir={args.compat_dir.resolve()}']
    preflight = subprocess.run([sys.executable, 'build-smart.py', f':{target}:codexClientTestClasses',
                                f':{target}:codexTestClasses', *flags], cwd=ROOT,
                               capture_output=True, text=True, timeout=240)
    if preflight.returncode:
        print(preflight.stdout[-2500:])
        return 1
    for phase in (['initial', 'restart'] if args.phase == 'all' else [args.phase]):
        control = folder / 'control'
        if control.exists():
            shutil.rmtree(control)
        control.mkdir()
        server = folder / 'server'
        server.mkdir(exist_ok=True)
        installer = __import__('runpy').run_path(str(ROOT / 'scripts/install-examples.py'))['install']
        for example in ('vanilla-enhanced', 'framework-showcase'):
            installer(example, server / 'codex/advancements')
        (server / 'results.json').unlink(missing_ok=True)
        server_log = server / 'logs/latest.log'
        server_log.unlink(missing_ok=True)
        processes, streams = [], []
        try:
            for actor in ('server', 'alice', 'bob'):
                if actor != 'server':
                    deadline = time.monotonic() + 150
                    while not server_log.exists() or 'Done (' not in server_log.read_text(errors='replace'):
                        if processes[0].poll() is not None:
                            raise RuntimeError('Dedicated test server exited before becoming ready')
                        if time.monotonic() > deadline:
                            raise TimeoutError('Dedicated test server startup timed out')
                        time.sleep(1)
                task = 'runCodexTest' if actor == 'server' else 'runCodexTransportClient'
                options = [f'-Pcodex_test_phase={phase}', '-Pcodex_test_world=world-a'] if actor == 'server' else [f'-Pcodex_test_actor={actor}']
                stream = (folder / f'{phase}-{actor}.log').open('w', encoding='utf-8')
                streams.append(stream)
                processes.append(subprocess.Popen([sys.executable, 'build-smart.py', f':{target}:{task}', *flags, *options], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT))
            deadline = time.monotonic() + 480
            report = server / 'results.json'
            while not report.exists():
                if any(p.poll() not in (None, 0) for p in processes):
                    raise RuntimeError('A test process failed; inspect actor logs')
                if time.monotonic() > deadline:
                    raise TimeoutError('Real-client test timed out')
                time.sleep(1)
            data = json.loads(report.read_text())
            (folder / f'results-{phase}.json').write_text(json.dumps(data, indent=2) + '\n')
            print(f'{args.loader}/{phase}: {len(data["passed"])} passed, {len(data["failures"])} failed (TWO ACTUAL CLIENTS)', flush=True)
            if data['failures']:
                print('\n'.join(data['failures']))
                return 1
            for process in processes:
                try:
                    process.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    terminate(process)
        except Exception as error:
            print(f'FAIL {args.loader}/{phase}: {error}; inspect {folder}', flush=True)
            return 1
        finally:
            for process in processes:
                terminate(process)
            for stream in streams:
                stream.close()
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
