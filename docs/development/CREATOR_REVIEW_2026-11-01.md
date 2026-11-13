# Creator milestone review — November 1, 2026

This is the review for the completed creator/reliability round, following the navigation fixes. These are actual rendered NeoForge 26.1.2 screenshots from an isolated dedicated server with two actual clients. The driver presses real Minecraft widget callbacks inside its own client; it does not control the desktop mouse or keyboard. Screenshots show disposable test quests where named GUI Story Test / Reward Scenario; the normal development examples are listed below.

## Where to review in your development world

Launch the regular Fabric or NeoForge Gradle/IntelliJ client again to load the rebuilt classes and seed new missing examples. Existing edited examples win over updates. Do not expect an already running client to hot-load Java changes.

- **Minecraft: Field Notes / Story:** all 16 native Story advancements now have editable presentation examples. Additional examples cover Adventure root, four Nether milestones, two End milestones and planting a seed: 24 total. Native objectives/parents/AND-OR groups are retained.
- **Sagittary / Sleep Cycle:** obtain a fletching table / any bed to expose native tabs normally, or use Edit Mode to inspect the full trees immediately. The unmodified companion JARs load as development dependencies. Six optional narrative sidecars leave their original advancement JSON untouched.
- **Camp Chronicle:** the separate story tab. Choose a branch at its root; the other branch stays readable and visibly locked. The eight original demo quests remain, with these additions: **Timber with a Story** (log tag plus named oak requirement), **A Share for Everyone** (each participant claims once), **Letters from the Road** (condition-based replies), **A Heading of Your Own** (obtain a compass for a configured heading/sound).

Untouched legacy Field Journal examples are retired with backups. Edited legacy trees remain readable instead of being deleted. Production JARs do not install demonstrations or companion mods.

## Dialogue and exclusive chapters in the GUI

Open Edit Mode → select a node → Quest Story & Rules → Choices → Add Choice. Write the label/outcome, choose Next Chapter and leave **Chapter requires this choice** enabled to create its gate. Source and destination become drafts together. Apply to Draft, then Save the main editor. Show Choice When adds accepted/completed/claimed/previous-choice conditions using quest pickers, required groups and alternatives.

![Choice label, linked chapter, exclusive gate and outcome editor](../../runs/transport/neoforge/alice/screenshots/creator-story-choice.png)

The test additionally checks the saved server definitions for the introduction, choice, condition, destination gate and milestone, then reconnects both clients after a dedicated restart.

![A required completed-quest condition](../../runs/transport/neoforge/alice/screenshots/creator-condition.png)

At 520×240, the outcome gets its own page so the footer remains accessible:

![Compact choice controls](../../runs/transport/neoforge/alice/screenshots/creator-compact-choice.png)

![Compact outcome text page](../../runs/transport/neoforge/alice/screenshots/creator-compact-outcome.png)

This establishes that the supported story/branch forms are reproducible through the GUI. A broader pack-maker usability review is still useful; unsupported arbitrary native predicates retain the advanced escape hatch.

## Deliver tags and matching items

Deliver Items → Add Tag / Add Item → Props. Choose a quantity, name/potion/damage or copy explicit held-item components. No registry syntax is required for those controls. A delivery can contain both a broad log tag and a named oak log; the server allocates inventory before consuming it so one item cannot satisfy both rows.

![Visual tag and matching-property item rows](../../runs/transport/neoforge/alice/screenshots/creator-delivery-tags.png)

[Property controls](../../runs/transport/neoforge/alice/screenshots/creator-delivery-properties.png). Timber with a Story reproduces this combination in Chronicle. Multi-objective quests expose a delivery selector instead of inaccessible overlapping submission buttons.

## Creator tools

Edit Mode → **Tools** (header) or **Pack Creator Tools** (sidebar):

- Problems: clickable parent/requirement/story/gate/loot diagnostics for the current snapshot.
- Preview: local Fresh/Accepted/Completed/Claimed and choice states, without modifying real progress.
- Duplicate: copy a quest/subtree, preserving unknown fields and remapping internal parent/story/gate links.
- Bulk Rules: apply acceptance/manual-claim rules; defaults keep current values. Linked/copy/bulk drafts have batch Undo.
- Migrate: export a fingerprinted offline custom-ID/criterion-key migration plan and the maintained Python tool. Explicit closed worlds, preview and backups are required; native fallback IDs stay stable.

[Problems](../../runs/transport/neoforge/alice/screenshots/creator-problems.png), [fresh preview](../../runs/transport/neoforge/alice/screenshots/creator-preview-fresh.png), [completed preview](../../runs/transport/neoforge/alice/screenshots/creator-preview-complete.png), [duplicate](../../runs/transport/neoforge/alice/screenshots/creator-duplicate.png), [bulk rules](../../runs/transport/neoforge/alice/screenshots/creator-bulk-rules.png).

![Exported offline migration plan](../../runs/transport/neoforge/alice/screenshots/creator-migration-exported.png)

The criterion dialog also has Copy Existing Native Objective: select an advancement by title, then its criterion. The test copies and saves an objective and checks both native criteria remain in the saved definition. Existing ordinary criterion keys are protected against accidental renames. [Full migration instructions](CREATOR_TOOLS.md#offline-quest-and-criterion-migrations).

## Shared rewards and local presentation

Rules → Repeatable → Party → **Each participant** gives accepted members their own once-only share while the objective tally is shared. Offline shares are retained; the next cycle waits for remaining members' claims. Leaving forfeits an unclaimed share. The existing One Group Set policy stays the default. Policy edits apply next cycle.

Effects configures an optional heading and sound. Preview is local, respects notice/sound/animation settings and grants nothing. A Heading of Your Own is the in-game example; obtain a compass in the disposable review world.

![Local authored heading preview](../../runs/transport/neoforge/alice/screenshots/creator-celebration.png)

Reward Preview now distinguishes manual claims and explains named pool rolls, quantity ranges and weighted chances without rolling loot:

![Reward icons and weighted treasure details](../../runs/transport/neoforge/alice/screenshots/creator-reward-preview.png)

Existing upper-right adjacent dots, sprite-shaped lock shading, local green hold sweep, immediate completion refresh, hover-card input priority and exact delivery receipts remain. See the [earlier feedback review](JUICE_REVIEW_2026-10-22.md) and [navigation regression](NAVIGATION_REVIEW_2026-10-22.md) for those images.

## Validation evidence

- **Actual rendered clients:** Fabric 49 initial + six restart; NeoForge 49 initial + six restart. Total 110 passing checks, no failures. Loopback TCP, not simulated connections. Covers native party sharing, partial consumption, reward/duplicate claims, individual shared entitlements, 24 tree switches, choice save/branch locks, reconnect and dedicated restart, plus GUI authoring. The newest native-copy, compact-dialog and migration-export extensions ran on NeoForge.
- **Offline migration:** 32 focused checks passed, including locks, stale exports, claims, opaque data, rollback and idempotence.
- **Migration cold start:** eight migrated-world + five independent-world checks passed on NeoForge. These reconnects are simulated players loaded from copies of the actual clients' saved worlds. Native completion/criterion keys and claims survive; Alice cannot reclaim; Bob can claim his previously unclaimed reward once; the independent world inherits none of it.
- **Python example installer:** three tests passed.
- **Final simulated dedicated suite:** each loader passed 159 initial + 15 restart + nine independent-world checks, 366 total with no failures. These use real dedicated servers and simulated embedded player connections, separate from the 110 actual-client checks above.
- **Scale boundaries:** the final 501-node overview measured 65ms Fabric / 64ms NeoForge with two document reads. The latest NeoForge client rendered the 501-node editor; 10 FPS with a configured 15 FPS cap is only a smoke check, not a performance promise.

All reports/screenshots are local ignored artifacts under runs; none are bundled with the mod.

## Packaged builds

The final isolated `build-smart.py shadowJar --offline` passed for both loaders. Archive checks confirmed loader metadata, shared/client mixin declarations, new authoring/runtime classes and the exact maintained migration utility; validation/transport fixtures, demo data and companion mod resources are absent. Gameplay tests used the Gradle development profiles; the distribution archives received structural checks, not a separate production-launch session.

- [Fabric 26.1.2 JAR](../../runs/frame-mask-build/fabric/libs/codex-fabric-26.1.2-0.1.0.jar): 506,764 bytes.
- [NeoForge 26.1.2 JAR](../../runs/frame-mask-build/neoforge/libs/codex-neoforge-26.1.2-0.1.0.jar): 507,443 bytes.
- [Checksums and archive checks](../../runs/final-package-verification.json).

These outputs are isolated because an existing user client held the normal build JAR open. Fully exit that client and relaunch the regular Gradle/IntelliJ configuration to compile/load current Java changes. Resource reload alone cannot load changed Java classes. Nothing was committed, staged or published.

## Explicit remaining scope

No general dialogue graph canvas, arbitrary repeat native-trigger implementation, comprehensive modpack-scale/remote-latency benchmark, complete vanilla content overhaul or added Minecraft target. The requested Stonecutter template was unavailable, so no replacement infrastructure or speculative target was added. Interrupted arbitrary function effects still need operator review. [Current scope and next priorities](FEATURE_COMPLETION.md).
