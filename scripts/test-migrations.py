#!/usr/bin/env python3
"""Focused offline migration tests: opaque fields, independent worlds, claims, collisions and rollback."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import uuid

spec = importlib.util.spec_from_file_location('migration', Path(__file__).with_name('migrate-advancement.py'))
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(m.encoded(value))


def snapshot(root):
    return {str(p.relative_to(root)): p.read_bytes() for p in root.rglob('*') if p.is_file() and 'migration_backups' not in p.parts}


def run():
    passed = []
    def check(name, result):
        if not result:
            raise AssertionError(name)
        passed.append(name)
    with tempfile.TemporaryDirectory(prefix='codex-migration-') as folder:
        root = Path(folder); pack = root / 'pack'; worlds = [root / 'world-a', root / 'world-b', root / 'world-c']
        old, new = 'pack:old', 'pack:new'
        source = {'criteria': {'a': {'trigger': 'minecraft:impossible'}, 'b': {'trigger': 'minecraft:tick'}}, 'requirements': [['a', 'b']], 'rewards': {'loot': ['pack:codex_generated/old']}, 'unknown': {'parent': old, old: [1, 2, 3]}}
        write(m.resource(pack, old), source)
        write(m.resource(pack, 'pack:child'), {'parent': old, 'criteria': {'a': {'trigger': 'minecraft:tick'}}, 'unknown': {'quest': old}})
        write(pack / 'codex/quests.json', {old: {'reward_ids': ['item', 'xp', 'loot', 'function'], 'unknown': {old: 'keep'}}, 'pack:other': {'requires_choice': {'quest': old, 'choice': 'path'}, 'dialogue': {'choices': [{'id': 'go', 'label': 'Go', 'next_quest': old, 'when': [[{'quest': old, 'state': 'completed'}]]}]}}})
        write(pack / 'codex/claimable_rewards.json', {old: {'experience': 1, 'codex_items': [{'id': 'minecraft:diamond', 'count': 1}], 'loot': ['pack:codex_generated/old', 'pack:random'], 'function': 'pack:reward'}})
        write(pack / 'codex/submission_tasks.json', {old + '#a': {'items': [{'id': 'minecraft:stone', 'count': 5}]}})
        write(pack / 'codex/layout.json', {'positions': {old: {'x': 12, 'y': 34}}})
        write(pack / 'codex/treasure_lists.json', {old: {'name': 'Unrelated pool', 'entries': []}})
        write(m.resource(pack, 'pack:codex_generated/old', 'loot_table'), {'pools': [{'entries': [{'type': 'minecraft:item', 'name': 'minecraft:diamond'}]}]})
        unrelated = m.resource(pack, 'pack:unrelated')
        unrelated.parent.mkdir(parents=True, exist_ok=True)
        unrelated.write_bytes(b'{"criteria":{"x":{"trigger":"minecraft:tick"}},"unknown":{"literal":true}}\r\n')
        uid = str(uuid.uuid4())
        for index, world in enumerate(worlds):
            world.mkdir(); (world / 'level.dat').write_bytes(b'world-nbt-not-touched'); (world / 'session.lock').write_bytes(b'lock')
            write(world / 'players/advancements/player.json', {old: {'criteria': {'a': '2026-10-22 00:00:00 +0000'} if index != 1 else {}, 'done': index != 1}, 'other:goal': {'criteria': {'a': 'unchanged'}, 'done': True}, 'DataVersion': 1})
            write(world / 'codex/player_quests/player.json', {old: {'accepted': True, 'delivered': {'a': {'minecraft:stone': index + 1}}, 'completed_criteria': ['a'], 'baseline': {old + '#a': 7}, 'unknown': {'a': 1, old: 'keep'}}})
            write(world / 'codex/claims/player.json', {old: [0]})
            write(world / 'codex/claim_keys/player.json', {old: ['item']})
            write(world / 'codex/party_advancements/party.json', {old: ['a']})
            write(world / 'codex/submissions/player.json', {old + '#a': {'minecraft:stone': index + 1}, '@import/' + uid + '/' + old + '#a': {'minecraft:stone': 1}})
            write(world / 'codex/shared_cycles/pack/old' / uid / '0/codex/claim_keys/player.json', {old: ['item']})
            write(world / 'codex/mailbox/player.json', {'items': [{'id': 'minecraft:diamond', 'components': {'minecraft:custom_data': {'quest': old}}}]})
            write(m.resource(world / 'datapacks/codex_advancements', old), source)
        plan = {'id': str(uuid.uuid4()), 'source': old, 'target': new, 'criterion_from': 'a', 'criterion_to': 'renamed_a', 'has_fallback': False, 'reward_ids': ['item', 'xp', 'loot', 'function'], 'reward_count': 4, 'source_hash': m.fingerprint(m.resource(pack, old))}
        check('Related worlds cannot be silently left behind', worlds[2] in m.related_worlds(pack, worlds[:2], plan['id']))
        before = snapshot(root)
        changes, report = m.plan_changes(plan, pack, worlds[:2])
        check('Preview does not alter documents', snapshot(root) == before)
        check('Preview identifies both independent player files', report['player_progress_files'] == 2)
        def fail(index):
            if index == 3:
                raise OSError('Injected write failure')
        try:
            m.apply_changes(changes, pack, worlds[:2], fault=fail)
        except OSError:
            pass
        else:
            raise AssertionError('Fault injection did not fail')
        check('Failure restores all preimages and removes new documents', snapshot(root) == before)
        backup = m.apply_changes(changes, pack, worlds[:2])
        check('Success preserves a complete backup manifest', (backup / 'manifest.json').exists())
        renamed = m.read(m.resource(pack, new))
        check('Unrelated resource bytes are not reformatted', unrelated.read_bytes() == before['pack/data/pack/advancement/unrelated.json'])
        check('Unknown native fields remain unchanged', renamed['unknown'] == source['unknown'])
        check('Only source criterion and mixed requirements are renamed', 'renamed_a' in renamed['criteria'] and renamed['requirements'] == [['renamed_a', 'b']] and 'a' in m.read(m.resource(pack, 'pack:child'))['criteria'])
        check('Incoming native parent changes without touching opaque content', m.read(m.resource(pack, 'pack:child'))['parent'] == new and m.read(m.resource(pack, 'pack:child'))['unknown']['quest'] == old)
        rules = m.read(pack / 'codex/quests.json')
        check('Story links, gates and condition references move together', rules['pack:other']['requires_choice']['quest'] == new and rules['pack:other']['dialogue']['choices'][0]['next_quest'] == new and rules['pack:other']['dialogue']['choices'][0]['when'][0][0]['quest'] == new)
        check('Opaque quest metadata remains unchanged', rules[new]['unknown'] == {old: 'keep'})
        check('Unrelated treasure pool IDs are preserved', old in m.read(pack / 'codex/treasure_lists.json'))
        check('Generated reward table and both references move consistently', m.resource(pack, 'pack:codex_generated/new', 'loot_table').exists() and renamed['rewards']['loot'] == ['pack:codex_generated/new'] and m.read(pack / 'codex/claimable_rewards.json')[new]['loot'][0] == 'pack:codex_generated/new')
        check('Stable reward and legacy identities remain sealed', rules[new]['reward_ids'] == plan['reward_ids'] and rules[new]['reward_ids_legacy'] == plan['reward_ids'])
        a = m.read(worlds[0] / 'players/advancements/player.json'); b = m.read(worlds[1] / 'players/advancements/player.json')
        check('Native completion timestamps survive criterion/ID migration', a[new]['criteria']['renamed_a'] == '2026-10-22 00:00:00 +0000' and a[new]['done'])
        check('Second world does not inherit first-world progress', b[new]['criteria'] == {} and not b[new]['done'])
        check('Unrelated native progress survives', a['other:goal']['criteria'] == {'a': 'unchanged'})
        state = m.read(worlds[0] / 'codex/player_quests/player.json')[new]
        check('Delivery, completed criteria and statistic baselines migrate', state['delivered']['renamed_a']['minecraft:stone'] == 1 and state['completed_criteria'] == ['renamed_a'] and state['baseline'][new + '#renamed_a'] == 7)
        check('Unrelated state fields survive', state['unknown'] == {'a': 1, old: 'keep'})
        check('Claim identities and legacy index claims survive', m.read(worlds[0] / 'codex/claim_keys/player.json')[new] == ['item'] and m.read(worlds[0] / 'codex/claims/player.json')[new] == [0])
        check('Party partial criterion sharing follows renamed key', m.read(worlds[0] / 'codex/party_advancements/party.json')[new] == ['renamed_a'])
        check('Import-watermark tasks migrate without duplicating deliveries', '@import/' + uid + '/' + new + '#renamed_a' in m.read(worlds[0] / 'codex/submissions/player.json'))
        check('Shared-cycle claim archives move to the new resource path', (worlds[0] / 'codex/shared_cycles/pack/new' / uid / '0/codex/claim_keys/player.json').exists())
        check('Mailbox and world NBT stay byte-identical', (worlds[0] / 'codex/mailbox/player.json').read_bytes() == before['world-a/codex/mailbox/player.json'] and (worlds[0] / 'level.dat').read_bytes() == b'world-nbt-not-touched')
        check('World datapack is ready before next server start', m.resource(worlds[0] / 'datapacks/codex_advancements', new).exists() and not m.resource(worlds[0] / 'datapacks/codex_advancements', old).exists())
        again, _ = m.plan_changes(plan, pack, worlds[:2])
        check('Applying the same plan to migrated worlds is idempotent', not again)
        late, _ = m.plan_changes(plan, pack, [worlds[2]]); m.apply_changes(late, pack, [worlds[2]])
        check('The same history can migrate an explicitly supplied closed world later', new in m.read(worlds[2] / 'players/advancements/player.json') and m.resource(worlds[2] / 'datapacks/codex_advancements', new).exists())
        import subprocess, sys
        locker = subprocess.Popen([sys.executable, '-c', "import importlib.util,sys;from pathlib import Path;s=importlib.util.spec_from_file_location('m',sys.argv[1]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);locks=m.WorldLocks([Path(sys.argv[2])]);locks.__enter__();print('locked',flush=True);sys.stdin.read(1)", str(Path(m.__file__).resolve()), str(worlds[0])], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        try:
            assert locker.stdout.readline().strip() == 'locked'
            rejected = False
            try:
                with m.WorldLocks([worlds[0]]):
                    pass
            except OSError:
                rejected = True
            check('An active world lock from another process rejects migration', rejected)
        finally:
            locker.communicate('x', timeout=10)
        check('Resource traversal is rejected', rejects(lambda: m.resource(pack, 'pack:../escape')))
        conflict = copy.deepcopy(plan); conflict['id'] = str(uuid.uuid4()); conflict['source'] = new; conflict['target'] = 'pack:child'; conflict.pop('source_hash')
        check('Existing target IDs are rejected before mutation', rejects(lambda: m.plan_changes(conflict, pack, worlds[:2])))
        fallback = copy.deepcopy(conflict); fallback['target'] = 'pack:another'; fallback['has_fallback'] = True
        check('Native/mod overrides cannot be rekeyed', rejects(lambda: m.plan_changes(fallback, pack, worlds[:2])))
        stale = copy.deepcopy(fallback); stale['has_fallback'] = False; stale['source_hash'] = 'stale'
        check('Stale source exports are rejected', rejects(lambda: m.plan_changes(stale, pack, worlds[:2])))
    print(str(len(passed)) + ' migration checks passed')


def rejects(action):
    try:
        action()
    except ValueError:
        return True
    return False


if __name__ == '__main__':
    run()
