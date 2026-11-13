#!/usr/bin/env python3
"""Verify Codex's installable matrix JARs, not gameplay behavior."""
import json
from pathlib import Path
import re
import struct
from zipfile import BadZipFile, ZipFile

try:
    import tomllib
except ImportError:  # The established wrapper also supports Python 3.10.
    tomllib = None

ROOT = Path(__file__).resolve().parents[1]
MOD_ID = 'codex'
PACKAGE = 'net/vg/codex/'


def properties(path):
    return {key.strip(): value.strip() for line in path.read_text().splitlines()
            if '=' in line and not line.lstrip().startswith('#')
            for key, value in [line.split('=', 1)]}


def quoted(text, key):
    match = re.search(rf'^\s*{re.escape(key)}\s*=\s*"([^"\n]*)"', text, re.MULTILINE)
    assert match, f'Missing metadata field: {key}'
    return match.group(1)


def release_jar(matrix):
    """Select exactly one expected release; reject classifiers and stale versions."""
    jars = [p for p in (ROOT / 'build/libs' / matrix.stem).glob('*.jar')
            if not p.name.endswith(('-dev.jar', '-sources.jar', '-javadoc.jar',
                                    '-dev-shadow.jar', '-raw.jar'))]
    assert len(jars) == 1, f'Expected one release JAR: {jars}'
    root_pins = properties(ROOT / 'gradle.properties')
    minecraft, loader = matrix.stem.rsplit('-', 1)
    expected = f"{root_pins['archives_name']}-{loader}-{minecraft}-{root_pins['mod_version']}.jar"
    assert jars[0].name == expected, f'Wrong/stale release artifact: {jars[0]} (expected {expected})'
    return jars[0]


def inspect(matrix):
    pins = properties(matrix)
    assert matrix.stem == f"{pins['minecraft_version']}-{pins['loader']}", 'Target/property mismatch'
    artifact = release_jar(matrix)
    version = properties(ROOT / 'gradle.properties')['mod_version']
    loader = pins['loader']
    with ZipFile(artifact) as jar:
        names = jar.namelist(); files = set(names)
        assert len(names) == len(files), 'Duplicate archive entries'
        for name in ['ModMain', 'advancement/QuestTransactions', 'advancement/AdvancementDocument',
                     'network/QuestNetworking', 'client/AdvancementEditorClient',
                     'client/advancements/CodexAdvancementsScreen']:
            assert PACKAGE + name + '.class' in files, 'Missing class: ' + name
        classes = [n for n in names if n.endswith('.class')]
        native_neoforge = pins.get('codex_native_platform') == 'true'
        if native_neoforge:
            assert loader == 'neoforge'
            assert PACKAGE+'compat/earlyneoforge/NetworkManager.class' in files
            assert all(b'dev/architectury/' not in jar.read(n) for n in classes), 'Unexpected Architectury linkage on native target'
        assert all(n.startswith(PACKAGE) and '/validation/' not in n for n in classes), 'Bundled dependency/test classes'
        for name in classes:
            data = jar.read(name)
            assert data[:4] == b'\xca\xfe\xba\xbe'
            assert struct.unpack('>H', data[6:8])[0] == int(pins['java_version']) + 44, 'Wrong Java level: '+name
        for name in files:
            if name.endswith('.json'): json.loads(jar.read(name))
        mixins = json.loads(jar.read('codex.mixins.json'))
        assert mixins['required'] and mixins['compatibilityLevel'] == 'JAVA_'+pins['java_version']
        assert mixins['package'] == 'net.vg.codex.mixin'
        for side in ['mixins', 'client', 'server']:
            for mixin in mixins.get(side, []):
                assert (mixins['package']+'.'+mixin).replace('.', '/')+'.class' in files
        if loader == 'fabric':
            assert not {'META-INF/neoforge.mods.toml', 'META-INF/mods.toml'} & files
            meta = json.loads(jar.read('fabric.mod.json'))
            assert meta['id'] == MOD_ID and meta['version'] == version
            assert meta['depends']['minecraft'] == pins['minecraft_version']
            for dep, pin in [('java','java_version'),('fabricloader','fabric_loader_version'),('architectury','architectury_api_version')]:
                assert meta['depends'][dep] == '>='+pins[pin]
            assert meta['mixins'] == ['codex.mixins.json']
            for names in meta['entrypoints'].values():
                for name in names: assert name.replace('.', '/')+'.class' in files
            assert b'net/minecraft/client/' not in jar.read(PACKAGE+'fabric/ModMainFabric.class')
        else:
            filename = 'META-INF/mods.toml' if loader == 'forge' else 'META-INF/neoforge.mods.toml'
            assert 'fabric.mod.json' not in files
            text = jar.read(filename).decode()
            assert quoted(text,'modId') == MOD_ID and quoted(text,'version') == version
            assert '['+pins['minecraft_version']+']' in text, 'Wrong Minecraft constraint'
            if native_neoforge: assert 'modId = \"architectury\"' not in text, 'Native target must not require the incompatible dependency'
            if tomllib is not None: tomllib.loads(text)
            name = 'forge/ModMainForge.class' if loader == 'forge' else 'neoforge/ModMainNeoForge.class'
            assert PACKAGE+name in files
            assert b'net/minecraft/client/' not in jar.read(PACKAGE+name), 'Client-only bootstrap dependency'
        assert 'tools/codex/migrate-advancement.py' in files
        assert 'examples/codex/showcase/codex/quests.json' in files
        assert 'assets/codex/lang/en_us.json' in files
        assert any(n.startswith('LICENSE') for n in files)
        assert not any(n.endswith(('.jar','.java')) for n in files)
        for n in ['fabric.mod.json','META-INF/neoforge.mods.toml','META-INF/mods.toml','codex.mixins.json']:
            if n in files: assert b'${' not in jar.read(n), 'Unexpanded metadata: '+n
    print(f'{matrix.stem}: release metadata, classes, mixins and resources OK')
    return artifact


if __name__ == '__main__':
    import sys
    selectors = set(sys.argv[1:])
    matrices = [p for p in sorted((ROOT / 'gradle/matrix').glob('*.properties')) if not selectors or p.stem in selectors]
    assert not selectors or selectors == {p.stem for p in matrices}, 'Unknown target selector'
    assert matrices, 'No matrix properties found'
    for matrix in matrices:
        try:
            inspect(matrix)
        except (AssertionError, KeyError, ValueError, OSError, BadZipFile) as error:
            raise SystemExit(f'{matrix.stem}: {error}') from error
    print(f'ARTIFACT VERIFICATION PASS ({len(matrices)}/{len(matrices)})')
