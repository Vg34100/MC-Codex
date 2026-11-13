# Reusable validation — updated November 13, 2026

## Compact commands

Run from the repository root using Windows Python and the configured Java 25. Always use build-smart.py for Gradle work.

```text
python scripts/test-server.py all --reset --offline --compat-dir runs/compat-mods
python scripts/test-clients.py fabric --reset --offline --compat-dir runs/compat-mods
python scripts/test-clients.py neoforge --reset --offline --compat-dir runs/compat-mods
python scripts/test-migrations.py
python scripts/test-migration-server.py neoforge --offline
python -m unittest discover -s tests -p "test_*.py"
python build-smart.py :26.1.2-fabric:packageTarget :26.1.2-neoforge:packageTarget
```

If a running development client locks normal output JARs, append `--build-init runs/frame-mask-build.gradle` to the server/client/migration harnesses, and `--init-script runs/frame-mask-build.gradle` to build-smart.py. This session used that existing ignored local script to isolate outputs. Do not close unrelated Minecraft processes to release locks. Omit --offline only when dependencies/assets need downloading.

## Current matrix evidence

Harnesses accept `--minecraft-version` (default `26.1.2`). New reports use full target directories: `runs/validation/26.1.2-fabric/results-{initial,restart,independent}.json`; older loader-only reports are historical. The current 26.1.2 dedicated run passed **169 initial + 11 restart + 7 independent-world checks per loader**, 374 total. The same 374 checks also pass on 26.3. These use simulated players. The separate real-client visual probe passed 29 checks including styled tooltips, full gold selected/unselected frames, chapter switching, claim receipts, JEI hover lookup and native node synchronization while paused.

All sixteen targets now compile, package and pass artifact inspection. Full dedicated progression/restart/separate-world reports now pass on all sixteen targets (3,018 checks); preserved summaries are in runs/ports-20261113/dedicated/. Actual one-client 85-stage editor journeys pass on 1.20.1 Fabric, 26.1.1 NeoForge and 26.3 NeoForge, with reports/screenshots in runs/ports-20261113/gui/. Runtime coverage varies by target; compilation alone does not establish GUI behavior. See [version status](MULTIVERSION.md). The old local `frame-mask-build.gradle` workaround is not needed for the new target-specific outputs.

## Legacy transport and recipes

On 1.20.1, `all` selects Fabric and Forge. The simulated suite adds 13 split-packet checks to the existing 169 initial checks. It verifies large client-to-server and server-to-client messages, native packet limits, malformed fragments, player/channel isolation and pending-fragment cleanup. Restart and independent-world checks remain separate. These are embedded connections, not actual socket clients.

```text
python scripts/test-server.py all --minecraft-version 1.20.1 --reset --offline
python scripts/test-recipes.py all --minecraft-version 1.21.1
python scripts/test-bundled-examples.py all --minecraft-version 1.20.1
python scripts/test-clients.py fabric --minecraft-version 1.20.1 --gui-only --reset --offline
python build-smart.py matrix:package --offline
python scripts/verify-matrix-artifacts.py
```

Run builds sequentially when they share a build directory. Parallel Gradle invocations can race over prepared source trees. New test worlds use the native flat preset with structure generation disabled to avoid spending most of the run generating terrain; existing validation worlds retain their stored generator. Client test sources use the same version preparation as production sources and remain excluded from release jars.

## Simulated dedicated scenarios

`test-server.py` starts a real dedicated server with two simulated ServerPlayers using embedded network connections. This is **not two actual clients**. Tests use native registries, advancement triggers, inventories, recipes, loot, functions, events and saved files. NeoForge uses its official mock-connection configuration. Embedded login explicitly restores player NBT before placing the player, matching the load step normally performed during login configuration.

The historical November 1 baseline passed 159 initial + 15 restart + nine independent-world checks on each loader (366 total, no failures). The three phases are initial, dedicated restart using the same world, and a separate independent world. Reports are under `runs/validation/<loader>/results-{initial,restart,independent}.json`. `--reset` removes only that loader's disposable validation folder. Run initial before restart/independent; a new full run should use --reset. Timeout cleanup stops only the harness process tree.

Coverage includes native definitions/unknown JSON/AND-OR round trips, datapack reload, ordinary vanilla party sharing, partial and overlapping item/tag/property submissions, reward claims and duplicate rejection, treasure/bags/overflow, transaction fault boundaries, personal/shared cycles and policy snapshots, conditional dialogue, native branch gating, vanilla revoke resets, malformed packets/permissions, creator copy/gates, restart and world isolation. A 501-node server overview writes `performance.json`; this is a focused operation measurement, not a complete modpack benchmark.

## Two actual clients

`test-clients.py` starts an isolated dedicated server and **two rendered Minecraft clients over loopback TCP**, on Fabric, NeoForge or Forge. It opens game windows. Warn the user first. This profile uses in-process client widget callbacks and screenshots; it does not move the operating-system pointer or type into the desktop. Never silently substitute `scripts/client-window.ps1` or other desktop-control tools.

Each loader's completed profile records 49 initial checks and six restart checks in `runs/transport/<loader>/results-{initial,restart}.json`. The clients exercise invite/accept commands, native sharing, partial delivery/consumption, item/XP/recipe/function/loot claims, duplicate rejection, each-participant rewards, 24 repeated tree switches, saved choices, excluded-branch readability, reconnect and dedicated restart.

The operator client's GUI driver authors a story introduction, choice, condition, linked chapter gate and milestone through actual widgets and saves them to the server. NeoForge's final profile also copies a native objective, corrects an invalid duration, captures the compact 520×240 choice/outcome dialogs and exports a migration plan. It renders a 501-node editor tree with a low FPS cap; the recorded FPS is only a rendering smoke check. Screenshot files are `runs/transport/<loader>/alice/screenshots/creator-*.png`. [Review images and exact limits](CREATOR_REVIEW_2026-11-01.md).

These are real local clients, not remote internet or latency/loss tests. Party invitation buttons and every manual authoring path have not all been automated. GUI screenshots alone do not prove a click changed server state; the workflow assertion additionally checks the saved server definitions.

## Offline and runtime migration checks

`test-migrations.py` runs 32 focused stdlib tests: opaque-field preservation, native timestamps, independent worlds, claims, references/layout, party/import markers, cycle paths, generated-loot consistency, idempotence, later explicit worlds, stale plans, collisions/traversal/native-key rejection, a real subprocess world lock and injected-write rollback.

After a successful actual-client profile with its GUI-exported reward plan, `test-migration-server.py neoforge` clones that closed dedicated save and a closed independent validation world. It migrates a custom quest ID and criterion key in the copies, then cold-starts dedicated servers without reinstalling fixtures. The reconnecting players here are **simulated**, using the identities/saves produced by the actual clients. Reports are `runs/migration-validation/neoforge/results-{world-a,world-b}.json`. Source worlds and user saves are never migrated by this test. See [the migration workflow](CREATOR_TOOLS.md#offline-quest-and-criterion-migrations).

## Companion mods and examples

Put unmodified compatible JARs/dependencies under `runs/compat-mods/fabric` and `runs/compat-mods/neoforge`. The current profiles use Sagittary 2.0.1 and Sleep Cycle 1.0.5. The server compares loaded namespace counts, completes representative native inventory objectives, edits display data without changing criteria/requirements, and verifies reload/restart. JAR fingerprints are recorded in compatibility-mods.json; companion JARs are not redistributed in Codex.

Normal development launch configuration also seeds missing editable examples. Extended validation profiles include 24 vanilla definitions, six sidecar-only companion enhancements and the separate Camp Chronicle. The normal download defaults to six enhanced vanilla advancements and the twelve-quest Camp Chronicle; see the included-examples wiki page. [GUI recipes](GUI_AUTHORING_RECIPES.md) explain how to reproduce them. Untouched legacy Field Journal content was retired with a backup; edited legacy copies are preserved.

## Remaining manual review

- More window sizes and GUI scales, keyboard navigation and long/localized/rich text; compact dialogs and previously checked Comfortable/native scales are partial evidence.
- More native mod predicates, modded registries and hidden/frame combinations; no claim that every advancement in every mod was exercised.
- Larger packs/parties, delayed remote connections and disconnects during an outstanding edit/action. Run an uncapped rendering profile before claiming smooth performance.
- Artist/pack-maker review of the actual vanilla/story content and narrative choices.
- Operator usability for interrupted arbitrary functions; fault injection is not a power-loss guarantee.

Production packaging must exclude validation/transport/demo/partner resources, include both loader metadata/mixin configurations and the maintained offline migration utility, and avoid loading client-only classes on a dedicated server. See the current [multiversion evidence](MULTIVERSION.md); compilation, server startup, simulated progression and actual GUI checks are recorded separately.

## One-client editor walkthrough

`test-clients.py --gui-only` starts one actual rendered client over TCP and drives the same 85-stage authoring journey used by the two-client profile. Reports and screenshots are separate under `runs/gui-authoring/<target>/`. This verifies GUI widgets and server-side saved definitions; it is **not a multiplayer test**. The existing two-client mode remains available.

Test-only mixins cancel Minecraft mouse capture and request nonactivating windows (GLFW hints before 26.3, SDL hint on 26.3; the 26.1/26.2 GLFW helper is separate from older constructors). They are excluded from release jars. Always warn before launching test windows. The driver waits for the resource-loading overlay to finish before connecting, reports disconnect reasons, bounds login retries, and fails a stalled login after 90 seconds. No desktop mouse/keyboard commands are used.

November 13: the one-client 1.20.1 Fabric walkthrough passes all 85 stages and three top-level TCP/server verification checks. Actual screenshots cover branch conditions, linked chapters, delivery tags/properties, reward previews, copied native objectives and compact choice/outcome editors. It exposed and verified a production fix for delayed save/snapshot responses reopening the root editor over a child panel. Harness setup now explicitly enables manual claims and repeatability on a delivery objective before testing invalid-duration recovery. This is not a two-client multiplayer result.

The same 85-stage journey and three TCP/server checks also pass on 26.1.1 NeoForge using the native binding. The author is placed in Creative mode only for the editor portion so incidental mobs cannot close the screen. GLFW focus hooks account for the separate native createGlfwWindow helper on 26.1–26.2.

The 26.3 NeoForge one-client journey also passes all 85 stages and three TCP/server checks. Its disposable server explicitly sets white-list=false because native defaults now reject unlisted offline test actors. The test binding remains loopback-only; player servers are unaffected.

## RPG dialogue milestone — November 13

The authoring journey now has 113 stages. It also reads the shipped Camp Chronicle through the RPG panel, skips/reveals and advances passages, selects a branch, verifies the saved outcome, opens/accepts its chapter, checks a compact window, returns through Quest Details, and creates speaker name/icon/reveal settings using ordinary GUI controls. The server verifies those fields, acceptance and the alternate-branch lock. Both 26.1.2 Fabric and NeoForge pass, each with one actual TCP client and three top-level verification checks. The first Fabric capture precedes the final content-sized layout polish; NeoForge verifies that final layout. Reports and actual captures are under runs/rpg-dialogue-20261113/gui/.

No desktop input commands are used. This is not a new two-client multiplayer result. Previous sixteen-target progression evidence remains recorded separately.
