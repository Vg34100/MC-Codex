#!/usr/bin/env python3
"""Install optional editable 26.1.2 examples into a Codex authoring pack. Stop the server first.

python scripts/install-examples.py story-demo --pack-dir <game-directory>/codex/advancements
python scripts/install-examples.py vanilla-enhanced --pack-dir <game-directory>/codex/advancements
Existing conflicting files/sidecar entries require --replace and receive .example.bak backups.
"""
import argparse
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]


def install(example, destination, replace=False):
    # Preflight every conflict before copying any files.
    planned = []
    for source in sorted((ROOT / 'examples' / example).rglob('*')):
        if not source.is_file():
            continue
        relative = source.relative_to(ROOT / 'examples' / example)
        target = destination / relative
        content = source.read_text(encoding='utf-8')
        if target.exists() and source.suffix == '.json' and relative.parts[0] == 'codex':
            merged = json.loads(target.read_text(encoding='utf-8'))
            incoming = json.loads(content)
            conflicts = [key for key in incoming if key in merged and merged[key] != incoming[key]]
            if conflicts and not replace:
                raise ValueError(f'Conflicting entries in {target}: {", ".join(conflicts)}. Use --replace to back up and replace them.')
            merged.update(incoming)
            content = json.dumps(merged, indent=2) + '\n'
        elif target.exists() and target.read_text(encoding='utf-8') != content and not replace:
            if relative.name == 'pack.mcmeta':
                continue  # Codex regenerates the correct pack metadata during synchronization.
            raise ValueError(f'Existing file differs: {target}. Use --replace to back up and replace it.')
        planned.append((target, content))
    for target, content in planned:
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            shutil.copy2(target, target.with_name(target.name + '.example.bak'))
        target.write_text(content, encoding='utf-8')
    return len(planned)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('example', choices=['story-demo', 'vanilla-enhanced', 'framework-showcase'])
    parser.add_argument('--pack-dir', type=Path, required=True)
    parser.add_argument('--replace', action='store_true')
    args = parser.parse_args()
    try:
        count = install(args.example, args.pack_dir, args.replace)
    except (ValueError, OSError) as error:
        parser.exit(1, str(error) + '\n')
    print(f'Installed {count} files. Start/reload Codex, then open Edit Mode to customize them.')


if __name__ == '__main__':
    main()
