# Codex

Codex extends Minecraft's advancements into an in-game quest authoring system. Vanilla, datapack and mod advancements remain ordinary advancements; optional Codex metadata adds deliveries, statistic objectives, claimable/random rewards, repeat cycles, optional story choices, treasure bags, tracking, custom layouts and persistent parties.

Playtested target: **Minecraft 26.1.2, Fabric and NeoForge**. Stonecutter builds also compile and package for 26.1, 26.1.1, 26.2 and 26.3 on both loaders; gameplay validation varies. [Version status and commands](docs/development/MULTIVERSION.md). This is an unfinished quest framework, not a feature-complete FTB Quests replacement.

- [Recipe editor](docs/wiki/recipe-editor.md)
- [Included examples](docs/wiki/included-examples.md)
- [Authoring and examples](docs/wiki/quest-authoring.md)
- [Testing commands and GUI checklist](docs/development/TESTING.md)
- [Implemented scope and remaining work](docs/development/FEATURE_COMPLETION.md)
- [Continuing-session checkpoint](docs/development/SESSION_PROGRESS.md)

Open Advancements and enable Edit Mode with operator permissions, or use `/codex advancements edit`.

The downloaded mod and normal IDE clients include **six enhanced vanilla advancements** and the separate twelve-quest **Camp Chronicle** tab. [See exactly what is included and how to disable it](docs/wiki/included-examples.md). Existing authored content is preserved, so older development installations may retain their previous larger examples. Optional local Sagittary/Sleep Cycle JARs remain development dependencies; no companion mods are bundled. Their original advancement trees work when the mods are installed separately.

[Recreate the examples through the GUI](docs/development/GUI_AUTHORING_RECIPES.md), [use the creator tools](docs/development/CREATOR_TOOLS.md), or [view the current actual-game screenshots](docs/development/CREATOR_REVIEW_2026-11-01.md). Comfortable Codex scaling is independent of Minecraft's Large GUI setting; configure it through the header **UI** button.

Build a target with `python build-smart.py :26.1.2-fabric:packageTarget` on Windows using Java 25, or compile the ten modern targets with `python build-smart.py compile:modern`. Artifacts are under `build/libs/<minecraft>-<loader>/`. Unqualified matrix commands include six legacy targets whose API ports are unfinished; use explicit targets while that work continues. Nothing in this session publishes artifacts.
