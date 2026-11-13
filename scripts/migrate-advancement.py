#!/usr/bin/env python3
"""Preview/apply a GUI-exported advancement migration to CLOSED, explicitly selected worlds.

python scripts/migrate-advancement.py --plan PLAN --pack PACK --world WORLD [--world WORLD2]
Add --apply after reviewing the preview. --recover restores an interrupted operation's backups.
Never edits player inventories, XP, statistics, mailbox contents, or arbitrary function commands.
"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import uuid

ID = re.compile(r'^[a-z0-9_.-]+:[a-z0-9/._-]+$')


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def identifier(value):
    if not ID.fullmatch(value) or value.split(':', 1)[0] in ('.', '..') or any(p in ('', '.', '..') for p in value.split(':', 1)[1].split('/')):
        raise ValueError('Invalid resource identifier: ' + value)
    return value


def resource(root, value, kind='advancement'):
    namespace, name = identifier(value).split(':', 1)
    return root / 'data' / namespace / kind / (name + '.json')


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temporary = tempfile.mkstemp(prefix='.codex-migration-', dir=path.parent)
    try:
        with os.fdopen(handle, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        Path(temporary).unlink(missing_ok=True)


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def rekey(mapping, old, new):
    result = copy.deepcopy(mapping)
    if old != new and old in result:
        if new in result:
            raise ValueError('Destination saved entry already exists: ' + new)
        result[new] = result.pop(old)
    return result


def task_keys(mapping, old, new, old_key, new_key):
    result = {}
    for name, value in mapping.items():
        prefix = ''
        task = name
        if name.startswith('@import/'):
            parts = name.split('/', 2)
            if len(parts) == 3:
                prefix, task = '/'.join(parts[:2]) + '/', parts[2]
        quest, separator, criterion = task.partition('#')
        target = prefix + new + '#' + (new_key if old_key and criterion == old_key else criterion) if separator and quest == old else name
        if target != name and target in mapping or target in result:
            raise ValueError('Destination task already exists: ' + target)
        result[target] = copy.deepcopy(value)
    return result


def quest_refs(metadata, old, new):
    if metadata.get('requires_choice', {}).get('quest') == old:
        metadata['requires_choice']['quest'] = new
    for choice in metadata.get('dialogue', {}).get('choices', []):
        if choice.get('next_quest') == old:
            choice['next_quest'] = new
        for group in choice.get('when', []):
            for condition in group:
                if condition.get('quest') == old:
                    condition['quest'] = new


def transform(value, old, new, old_key='', new_key='', kind='claims'):
    if kind == 'advancement':
        result = copy.deepcopy(value)
        if result.get('parent') == old:
            result['parent'] = new
        return result
    if kind == 'source':
        result = transform(value, old, new, kind='advancement')
        if old_key:
            result['criteria'] = rekey(result['criteria'], old_key, new_key)
            if 'requirements' in result:
                result['requirements'] = [[new_key if c == old_key else c for c in group] for group in result['requirements']]
        return result
    if kind == 'tasks':
        return task_keys(value, old, new, old_key, new_key)
    if kind == 'layout':
        result = copy.deepcopy(value)
        if 'positions' in result:
            result['positions'] = rekey(result['positions'], old, new)
        return result
    result = rekey(value, old, new)
    if kind == 'quests':
        for metadata in result.values():
            quest_refs(metadata, old, new)
    elif kind == 'native':
        if new in result and old_key:
            result[new]['criteria'] = rekey(result[new].get('criteria', {}), old_key, new_key)
    elif kind == 'party':
        if new in result and old_key:
            result[new] = [new_key if c == old_key else c for c in result[new]]
    elif kind == 'state':
        for quest, state in result.items():
            if quest == new and old_key:
                if 'delivered' in state:
                    state['delivered'] = rekey(state['delivered'], old_key, new_key)
                if 'completed_criteria' in state:
                    state['completed_criteria'] = [new_key if c == old_key else c for c in state['completed_criteria']]
            if 'baseline' in state:
                state['baseline'] = task_keys(state['baseline'], old, new, old_key, new_key)
            for section in ('baselines', 'stat_values'):
                for member, values in state.get(section, {}).items():
                    state[section][member] = task_keys(values, old, new, old_key, new_key)
    return result


SIDECARS = {'quests.json': 'quests', 'claimable_rewards.json': 'claims', 'submission_tasks.json': 'tasks', 'statistic_tasks.json': 'tasks', 'layout.json': 'layout'}


def fingerprint(path):
    return hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else '-'


def reward_count(rewards, old):
    generated = old.split(':', 1)[0] + ':codex_generated/' + old.split(':', 1)[1]
    return ((1 if rewards.get('experience', 0) > 0 else 0) + len(rewards.get('codex_items', [])) +
            len(rewards.get('recipes', [])) + sum(item != generated for item in rewards.get('loot', [])) +
            (1 if rewards.get('function') else 0))


def plan_changes(plan, pack, worlds):
    old = identifier(plan['source']); new = identifier(plan.get('target', old))
    old_key, new_key = plan.get('criterion_from', ''), plan.get('criterion_to', '')
    if bool(old_key) != bool(new_key) or old_key and (not new_key.strip() or len(new_key) > 256):
        raise ValueError('Criterion migrations need both original and new keys')
    if old == new and (not old_key or old_key == new_key):
        raise ValueError('Choose a different quest ID or criterion key')
    if old != new and old.split(':', 1)[0] == 'minecraft':
        raise ValueError('Keep native vanilla IDs stable; duplicate their tree instead')
    history_path = pack / 'codex/migration_history.json'
    history = read(history_path) if history_path.exists() else {}
    operation = str(uuid.UUID(plan['id'])) if plan.get('id') else hashlib.sha256(encoded({k: plan.get(k, '') for k in ('source', 'target', 'criterion_from', 'criterion_to')})).hexdigest()
    previous = history.get(operation)
    source = resource(pack, old)
    if previous:
        definition = previous['definition_before']
    else:
        if not source.is_file():
            raise ValueError('Save the source into the authoring pack before exporting/migrating')
        definition = read(source)
        if plan.get('has_fallback', True):
            raise ValueError('Keep native/mod/datapack keys stable; duplicate their definitions instead')
        checks = {'source_hash': source, 'rules_hash': pack / 'codex/quests.json', 'rewards_hash': pack / 'codex/claimable_rewards.json', 'generated_hash': resource(pack, old.split(':', 1)[0] + ':codex_generated/' + old.split(':', 1)[1], 'loot_table')}
        for field, path in checks.items():
            if field in plan and plan[field] != fingerprint(path):
                raise ValueError('Saved source changed since export; export a fresh plan: ' + field)

        if old != new and resource(pack, new).exists():
            raise ValueError('Destination advancement already exists')
        if old_key and (old_key not in definition.get('criteria', {}) or new_key in definition.get('criteria', {})):
            raise ValueError('Original criterion missing or destination criterion already exists')
        expected = plan.get('criteria_before')
        if expected is not None and expected != definition.get('criteria'):
            raise ValueError('Source objectives changed since export; save and export again')
    changes = {}
    def put(path, value):
        data = None if value is None else encoded(value)
        before = path.read_bytes() if path.exists() else None
        if data != before:
            changes[path] = data
    # Existing migration history is a record, never itself rewritten as quest state.
    if not previous:
        for file in sorted(pack.rglob('*.json')):
            relative = file.relative_to(pack)
            if 'migration_backups' in relative.parts or relative == Path('codex/migration_history.json') or relative == Path('codex/migration_pending.json'):
                continue
            if relative.parts[0] not in ('codex', 'data'):
                continue
            if relative.parts[0] == 'codex' and relative.name not in SIDECARS or relative.parts[0] == 'data' and '/advancement/' not in '/' + relative.as_posix():
                continue
            value = read(file)
            if file == source:
                put(resource(pack, new), transform(value, old, new, old_key, new_key, 'source'))
                if old != new:
                    put(file, None)
            else:
                transformed = transform(value, old, new, old_key, new_key, SIDECARS[relative.name] if relative.parts[0] == 'codex' else 'advancement')
                if transformed != value:
                    put(file, transformed)
        if old != new:
            # Generated item loot remains available under the old ID for external references.
            old_ns, old_path = old.split(':', 1); new_ns, new_path = new.split(':', 1)
            original_table = resource(pack, old_ns + ':codex_generated/' + old_path, 'loot_table')
            if original_table.exists():
                target_table = resource(pack, new_ns + ':codex_generated/' + new_path, 'loot_table')
                if target_table.exists():
                    raise ValueError('Destination generated loot table already exists')
                put(target_table, read(original_table))
                destination = resource(pack, new)
                advanced = json.loads(changes[destination])
                if advanced.get('rewards', {}).get('loot'):
                    advanced['rewards']['loot'] = [new_ns + ':codex_generated/' + new_path if table == old_ns + ':codex_generated/' + old_path else table for table in advanced['rewards']['loot']]
                    put(destination, advanced)
            # The sidecar must exclude the renamed generated table from reward enumeration too.
            claimable_file = pack / 'codex/claimable_rewards.json'
            if claimable_file.exists():
                migrated = json.loads(changes.get(claimable_file, claimable_file.read_bytes()))
                if new in migrated and 'loot' in migrated[new]:
                    migrated[new]['loot'] = [new_ns + ':codex_generated/' + new_path if table == old_ns + ':codex_generated/' + old_path else table for table in migrated[new]['loot']]
                    put(claimable_file, migrated)
            rewards_file = pack / 'codex/claimable_rewards.json'
            reward_map = read(rewards_file) if rewards_file.exists() else {}
            if old in reward_map:
                rules_file = pack / 'codex/quests.json'
                rules = json.loads(changes.get(rules_file, rules_file.read_bytes() if rules_file.exists() else b'{}'))
                metadata = rules.setdefault(new, {})
                keys = plan['reward_ids'] if 'reward_ids' in plan else metadata.get('reward_ids')
                if keys is None or len(keys) != plan.get('reward_count', reward_count(reward_map[old], old)) or len(keys) != len(set(keys)):
                    raise ValueError('Export stable reward identities from the GUI before renaming a claimable quest')
                metadata['reward_ids'] = keys
                metadata.setdefault('reward_ids_legacy', keys)
                put(rules_file, rules)
        history[operation] = {'source': old, 'target': new, 'criterion_from': old_key, 'criterion_to': new_key, 'definition_before': definition}
        put(history_path, history)
    affected_players = 0
    for world in worlds:
        marker = world / 'codex/applied_migrations.json'
        applied = read(marker) if marker.exists() else {}
        if operation in applied:
            continue
        for directory in ('players/advancements', 'advancements', 'codex'):
            folder = world / directory
            if not folder.exists():
                continue
            for file in sorted(folder.rglob('*.json')):
                relative = file.relative_to(world)
                if any(part in relative.parts for part in ('reset_backups', 'migration_backups')) or file == marker:
                    continue
                if 'mailbox' in relative.parts or 'reward_effects' in relative.parts or file.name in ('state_version.json', 'transaction.json'):
                    continue
                kind = 'native' if directory != 'codex' else {'player_quests': 'state', 'party_quests': 'state', 'claims': 'claims', 'claim_keys': 'claims', 'submissions': 'tasks', 'party_advancements': 'party'}.get(file.parent.name)
                if kind is None:
                    continue
                value = read(file)
                changed = transform(value, old, new, old_key, new_key, kind)
                target = file
                if old != new and len(relative.parts) >= 4 and relative.parts[1] in ('cycles', 'shared_cycles'):
                    old_ns, old_path = old.split(':', 1); new_ns, new_path = new.split(':', 1)
                    prefix = world / 'codex' / relative.parts[1] / old_ns / old_path
                    try:
                        tail = file.relative_to(prefix)
                    except ValueError:
                        pass
                    else:
                        try:
                            uuid.UUID(tail.parts[0])
                            is_cycle = len(tail.parts) >= 4 and tail.parts[1].isdigit() and tail.parts[2] == 'codex'
                        except (ValueError, IndexError):
                            is_cycle = False
                        if not is_cycle:
                            if changed != value:
                                put(file, changed)
                            continue
                        target = world / 'codex' / relative.parts[1] / new_ns / new_path / tail
                        if target.exists():
                            raise ValueError('Destination cycle history already exists')
                if changed != value or target != file:
                    put(target, changed)
                if target != file:
                    put(file, None)
                if changed != value and directory != 'codex':
                    affected_players += 1
        if (world / 'codex/transaction.json').exists():
            raise ValueError('Recover the pending quest transaction before migrating')
        # Synchronize even when this plan was already applied to the global pack earlier.
        world_pack = world / 'datapacks/codex_advancements'
        if world_pack.exists():
            paths = {p for p in (pack / 'data').rglob('*') if p.is_file()} if (pack / 'data').exists() else set()
            paths.update(pack / 'codex' / name for name in SIDECARS if (pack / 'codex' / name).exists())
            paths.update(p for p in changes if p.is_relative_to(pack) and p.relative_to(pack).parts[0] == 'data')
            for path in paths:
                content = changes.get(path, path.read_bytes() if path.exists() else None)
                target = world_pack / path.relative_to(pack)
                if content != (target.read_bytes() if target.exists() else None):
                    changes[target] = content
            if old != new and resource(world_pack, old).exists():
                changes[resource(world_pack, old)] = None
        applied[operation] = {'source': old, 'target': new}
        put(marker, applied)
    return changes, {'operation': operation, 'files': len(changes), 'player_progress_files': affected_players, 'worlds': [str(w) for w in worlds], 'paths': [str(p) for p in changes]}


def function_references(pack, plan):
    old = re.escape(plan['source'])
    criterion = plan.get('criterion_from', '')
    patterns = [re.compile(r'\bonly\s+' + old + r'(?![a-z0-9_./:-])'), re.compile(r'advancements\s*=\s*\{[^}]*' + old + r'(?![a-z0-9_./:-])')]
    if plan.get('target', plan['source']) == plan['source']:
        patterns = [re.compile(r'\bonly\s+' + old + r'\s+' + re.escape(criterion) + r'(?:\s|$)')] if criterion else []
    found = []
    for file in (pack / 'data').rglob('*.mcfunction'):
        content = file.read_text(encoding='utf-8')
        if any(pattern.search(content) for pattern in patterns):
            found.append(str(file))
    return found


def related_worlds(pack, supplied, operation):
    roots = {world.parent for world in supplied}
    if pack.name == 'advancements' and pack.parent.name == 'codex':
        game = pack.parent.parent
        roots.update((game, game / 'saves'))
    missing = set()
    for root in roots:
        if not root.is_dir():
            continue
        for world in root.iterdir():
            if world in supplied or not (world / 'level.dat').is_file():
                continue
            if not (world / 'datapacks/codex_advancements').is_dir() and not (world / 'codex/state_version.json').exists():
                continue
            marker = world / 'codex/applied_migrations.json'
            if marker.exists() and operation in read(marker):
                continue
            missing.add(world.resolve())
    return sorted(missing)


class WorldLocks:
    def __init__(self, worlds):
        self.worlds, self.streams = worlds, []
    def __enter__(self):
        try:
            for world in self.worlds:
                if not (world / 'level.dat').is_file():
                    raise ValueError('Not a Minecraft world: ' + str(world))
                if os.name != 'nt' and str(world).startswith('/mnt/'):
                    raise ValueError('Use Windows Python to verify Windows Minecraft world locks')
                lock = world / 'session.lock'
                stream = lock.open('r+b' if lock.exists() else 'w+b')
                if lock.stat().st_size == 0:
                    stream.write(b'\0'); stream.flush()
                stream.seek(0)
                self.streams.append(stream)
                if os.name == 'nt':
                    import msvcrt
                    msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.lockf(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except Exception:
            self.__exit__(None, None, None)
            raise
        return self
    def __exit__(self, *args):
        for stream in self.streams:
            stream.close()


def apply_changes(changes, pack, worlds, fault=None):
    pending = pack / 'codex/migration_pending.json'
    if pending.exists():
        raise ValueError('An interrupted migration exists; use --recover first')
    backup = pack / 'codex/migration_backups' / str(uuid.uuid4())
    backup.mkdir(parents=True)
    journal = {'worlds': [str(w) for w in worlds], 'files': []}
    for index, (path, data) in enumerate(changes.items()):
        old = path.read_bytes() if path.exists() else None
        entry = {'path': str(path), 'backup': str(backup / str(index)), 'existed': old is not None}
        if old is not None:
            atomic(Path(entry['backup']), old)
        journal['files'].append(entry)
    atomic(pending, encoded(journal))
    try:
        for index, (path, data) in enumerate(changes.items()):
            if data is None:
                path.unlink(missing_ok=True)
            else:
                atomic(path, data)
            if fault:
                fault(index)
    except BaseException:
        restore(journal)
        pending.unlink(missing_ok=True)
        raise
    atomic(backup / 'manifest.json', encoded(journal))
    pending.unlink()
    return backup


def restore(journal):
    for entry in journal['files']:
        target = Path(entry['path'])
        if entry['existed']:
            atomic(target, Path(entry['backup']).read_bytes())
        else:
            target.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path)
    parser.add_argument('--pack', type=Path, required=True)
    parser.add_argument('--world', type=Path, action='append', required=True)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--recover', action='store_true')
    args = parser.parse_args()
    pack = args.pack.resolve(); worlds = sorted(set(w.resolve() for w in args.world))
    try:
        with WorldLocks(worlds):
            if args.recover:
                pending = pack / 'codex/migration_pending.json'
                journal = read(pending)
                if set(journal['worlds']) != {str(w) for w in worlds}:
                    raise ValueError('Recovery needs the same world paths as the interrupted operation')
                restore(journal); pending.unlink(); print('Restored pre-migration backups.'); return 0
            if not args.plan:
                raise ValueError('--plan is required')
            changes, report = plan_changes(read(args.plan), pack, worlds)
            missing = related_worlds(pack, worlds, report['operation'])
            references = function_references(pack, read(args.plan))
            report['function_references_to_update'] = references
            report['additional_related_worlds'] = [str(w) for w in missing]
            print(json.dumps(report, indent=2))
            if args.apply and references:
                raise ValueError('Update the listed advancement command references before applying')
            if args.apply and missing:
                raise ValueError('Include all listed related worlds before applying this global authoring-pack migration')
            if args.apply:
                print('Backups:', apply_changes(changes, pack, worlds))
            else:
                print('Preview only. Add --apply with every affected world closed and listed.')
        return 0
    except (ValueError, OSError) as error:
        print('Migration rejected:', error)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
