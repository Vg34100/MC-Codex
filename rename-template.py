#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import shutil
from pathlib import Path

PLACEHOLDERS = {
    'template262': None,
    'Template 26.1.2': None,
    'template-26.1.2': None,
    'com.example.template': None,
}

TEXT_EXTENSIONS = {
    '.gradle', '.properties', '.json', '.toml', '.md', '.txt', '.java', '.gitignore', '.cfg', '.yml', '.yaml'
}

SKIP_DIRS = {'.git', '.gradle', 'build', '.idea', '.codex', '.agents', 'run'}
SKIP_FILES = {'gradle-wrapper.jar'}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description='Rename placeholder identifiers in the template-26.1.2 starter project.'
    )
    parser.add_argument('--mod-id', required=True, help='Final lowercase mod id, e.g. fishingfrenzy')
    parser.add_argument('--mod-name', required=True, help='Display name, e.g. Fishing Frenzy')
    parser.add_argument('--package', required=True, help='Java package, e.g. net.vg.fishingfrenzy')
    parser.add_argument(
        '--project-name',
        help='Project directory/rootProject.name. Defaults to the current directory name.',
    )
    parser.add_argument(
        '--maven-group',
        help='Gradle maven_group. Defaults to the package value.',
    )
    parser.add_argument(
        '--archives-name',
        help='Gradle archives_name. Defaults to the mod id.',
    )
    parser.add_argument(
        '--class-name',
        default='ModMain',
        help='Base class name used for starter entrypoints. Default: ModMain',
    )
    parser.add_argument(
        '--dry-run',
        action='store_true',
        help='Show what would change without writing files.',
    )
    return parser.parse_args()


def validate_args(args: argparse.Namespace) -> None:
    if not re.fullmatch(r'[a-z][a-z0-9_]*', args.mod_id):
        raise SystemExit('mod-id must match [a-z][a-z0-9_]*')
    if not re.fullmatch(r'[a-zA-Z_][a-zA-Z0-9_]*(\.[a-zA-Z_][a-zA-Z0-9_]*)+', args.package):
        raise SystemExit('package must look like com.example.mod')
    if not re.fullmatch(r'[A-Z][A-Za-z0-9_]*', args.class_name):
        raise SystemExit('class-name must look like ModMain')


def iter_files(root: Path):
    for path in root.rglob('*'):
        if any(part in SKIP_DIRS for part in path.parts):
            continue
        if path.is_file() and path.name not in SKIP_FILES:
            yield path


def is_text_file(path: Path) -> bool:
    if path.suffix in TEXT_EXTENSIONS:
        return True
    return path.name in {'AGENTS.md', 'README_TEMPLATE.md', 'build-smart.py', 'settings.gradle', 'gradlew', 'gradlew.bat'}


def replace_text(path: Path, replacements: dict[str, str], dry_run: bool) -> bool:
    if not is_text_file(path):
        return False
    text = path.read_text(encoding='utf-8')
    updated = text
    for old, new in replacements.items():
        updated = updated.replace(old, new)
    if updated == text:
        return False
    if not dry_run:
        path.write_text(updated, encoding='utf-8', newline='')
    return True


def rename_package_tree(root: Path, old_package: str, new_package: str, dry_run: bool) -> list[str]:
    changes: list[str] = []
    old_parts = old_package.split('.')
    new_parts = new_package.split('.')
    for module in ('common', 'fabric', 'neoforge'):
        module_root = root / module / 'src' / 'main' / 'java'
        old_dir = module_root.joinpath(*old_parts)
        new_dir = module_root.joinpath(*new_parts)
        if old_dir.exists():
            changes.append(f'move {old_dir.relative_to(root)} -> {new_dir.relative_to(root)}')
            if not dry_run:
                new_dir.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(old_dir), str(new_dir))
                for parent in old_dir.parents:
                    if parent == module_root or parent == root:
                        break
                    try:
                        parent.rmdir()
                    except OSError:
                        break
    return changes


def rename_modid_resource_tree(root: Path, old_modid: str, new_modid: str, dry_run: bool) -> list[str]:
    changes: list[str] = []
    old_dir = root / 'common' / 'src' / 'main' / 'resources' / 'assets' / old_modid
    new_dir = root / 'common' / 'src' / 'main' / 'resources' / 'assets' / new_modid
    if old_dir.exists():
        changes.append(f'move {old_dir.relative_to(root)} -> {new_dir.relative_to(root)}')
        if not dry_run:
            new_dir.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(old_dir), str(new_dir))
    return changes


def rename_entrypoint_classes(root: Path, class_name: str, dry_run: bool) -> list[str]:
    rename_specs = [
        (root / 'common' / 'src' / 'main' / 'java', 'TemplateMod.java', f'{class_name}.java'),
        (root / 'fabric' / 'src' / 'main' / 'java', 'TemplateModFabric.java', f'{class_name}Fabric.java'),
        (root / 'fabric' / 'src' / 'main' / 'java', 'TemplateModFabricClient.java', f'{class_name}FabricClient.java'),
        (root / 'neoforge' / 'src' / 'main' / 'java', 'TemplateModNeoForge.java', f'{class_name}NeoForge.java'),
    ]
    changes: list[str] = []
    for base_dir, old_name, new_name in rename_specs:
        for path in base_dir.rglob(old_name):
            new_path = path.with_name(new_name)
            changes.append(f'move {path.relative_to(root)} -> {new_path.relative_to(root)}')
            if not dry_run:
                path.rename(new_path)
    return changes


def main() -> None:
    args = parse_args()
    validate_args(args)
    root = Path(__file__).resolve().parent
    project_name = args.project_name or root.name
    replacements = {
        'template262': args.mod_id,
        'Template 26.1.2': args.mod_name,
        'template-26.1.2': project_name,
        'com.example.template': args.package,
        f'import {args.package}.TemplateMod;': f'import {args.package}.{args.class_name};',
        'template262-fabric-26.1.2': f'{args.archives_name or args.mod_id}-fabric-26.1.2',
        'template262-neoforge-26.1.2': f'{args.archives_name or args.mod_id}-neoforge-26.1.2',
        'archives_name=template262': f'archives_name={args.archives_name or args.mod_id}',
        'maven_group=com.example': f'maven_group={args.maven_group or args.package}',
        'public final class TemplateMod': f'public final class {args.class_name}',
        'private TemplateMod()': f'private {args.class_name}()',
        'TemplateMod.MOD_ID': f'{args.class_name}.MOD_ID',
        'TemplateMod.init()': f'{args.class_name}.init()',
        'TemplateModFabric': f'{args.class_name}Fabric',
        'TemplateModFabricClient': f'{args.class_name}FabricClient',
        'TemplateModNeoForge': f'{args.class_name}NeoForge',
    }

    touched: list[str] = []
    for path in iter_files(root):
        if replace_text(path, replacements, args.dry_run):
            touched.append(str(path.relative_to(root)))

    moves: list[str] = []
    moves.extend(rename_package_tree(root, 'com.example.template', args.package, args.dry_run))
    moves.extend(rename_modid_resource_tree(root, 'template262', args.mod_id, args.dry_run))
    moves.extend(rename_entrypoint_classes(root, args.class_name, args.dry_run))

    print('Updated files:')
    for item in sorted(touched):
        print(f'  {item}')
    print('Renamed paths:')
    for item in moves:
        print(f'  {item}')
    print('Done.')


if __name__ == '__main__':
    main()
