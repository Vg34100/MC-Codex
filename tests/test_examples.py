import json
from pathlib import Path
import runpy
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
install = runpy.run_path(str(ROOT / 'scripts' / 'install-examples.py'))['install']


class ExampleInstallationTests(unittest.TestCase):
    def test_sidecars_merge_without_losing_unrelated_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            pack = Path(directory)
            state = pack / 'codex' / 'claimable_rewards.json'
            state.parent.mkdir()
            state.write_text(json.dumps({'my_pack:quest': {'experience': 42}}), encoding='utf-8')
            install('story-demo', pack)
            install('vanilla-enhanced', pack)
            result = json.loads(state.read_text(encoding='utf-8'))
            self.assertEqual(result['my_pack:quest']['experience'], 42)
            self.assertIn('codex_demo:field_journal/supplies', result)
            self.assertIn('minecraft:story/mine_stone', result)

    def test_conflict_preflight_does_not_partially_install(self):
        with tempfile.TemporaryDirectory() as directory:
            pack = Path(directory)
            authored = pack / 'data' / 'minecraft' / 'advancement' / 'story' / 'mine_stone.json'
            authored.parent.mkdir(parents=True)
            authored.write_text('{"user_work":true}', encoding='utf-8')
            with self.assertRaises(ValueError):
                install('vanilla-enhanced', pack)
            self.assertEqual(authored.read_text(encoding='utf-8'), '{"user_work":true}')
            self.assertFalse((pack / 'codex').exists())

    def test_explicit_replacement_keeps_user_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            pack = Path(directory)
            authored = pack / 'data' / 'minecraft' / 'advancement' / 'story' / 'mine_stone.json'
            authored.parent.mkdir(parents=True)
            authored.write_text('{"user_work":true}', encoding='utf-8')
            install('vanilla-enhanced', pack, replace=True)
            self.assertEqual(authored.with_name(authored.name + '.example.bak').read_text(encoding='utf-8'), '{"user_work":true}')
            self.assertIn('criteria', json.loads(authored.read_text(encoding='utf-8')))


if __name__ == '__main__':
    unittest.main()
