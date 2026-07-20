# Codex

Run `python rename-template.py ...` before doing any real work in a copied project. The template still contains placeholder identifiers and starter class names.

## First step

Example:

```bash
python rename-template.py \
  --mod-id fishingfrenzy \
  --mod-name "Fishing Frenzy" \
  --package net.vg.fishingfrenzy \
  --project-name fishingfrenzy \
  --class-name FishingFrenzy
```

Defaults:

- `--project-name`: current directory name
- `--maven-group`: same as `--package`
- `--archives-name`: same as `--mod-id`
- `--class-name`: `ModMain`

What it updates:

- `mod id`
- display name
- Java package
- `rootProject.name`
- `archives_name`
- `maven_group`
- starter class names and file names
- Fabric and NeoForge metadata
- `assets/<modid>/...` path

Recommended flow after copying this template:

1. Run `python rename-template.py ...`
2. Read `AGENTS.md`
3. Run `cmd.exe /c "python build-smart.py compile"`
4. Start implementing the new mod

The template intentionally excludes the original mod implementation, generated data, build outputs, IDE files, agent state, and Git history.
