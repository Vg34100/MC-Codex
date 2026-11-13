# Stonecutter ports — November 13, 2026

The shared suite build is adopted from the existing local suite implementation. Sources remain canonical under common/main, common/client and the loader trees; native preparation applies narrowly scoped API adapters per target. Registering a node does not establish support.

| Minecraft | Loaders | Compile / JAR inspection | Dedicated evidence | Actual GUI evidence |
| --- | --- | --- | --- | --- |
| 26.1.2 | Fabric, NeoForge | Pass | 169 initial + 11 restart + 7 independent-world checks each | Existing two-client profiles; latest styled production-pack probe on Fabric |
| 26.1, 26.1.1 | Fabric, NeoForge | Pass | 169 initial + 11 restart + 7 independent-world checks on each of the four targets | 26.1.1 NeoForge: one actual client passes 85-stage editor journey and saved-data verification |
| 26.2 | Fabric, NeoForge | Pass | 169 initial + 11 restart + 7 independent-world checks each; bundled/native definitions 29 each | Not yet checked |
| 26.3 | Fabric, NeoForge | Pass | 35 bundled checks and 169 initial + 11 restart + 7 independent-world progression checks each | One actual NeoForge client: 85-stage editor journey and saved definitions pass |
| 1.21, 1.21.1 | Fabric, NeoForge | Pass | 169 initial + 11 restart + 7 independent-world checks each; 1.21.1 native recipe tests 19 each | Not yet checked |
| 1.20.1 | Fabric, Forge | Pass | Both dedicated startup/reload checks pass; native recipe tests 19 each; fresh progression 182 initial + 11 restart + 7 independent-world checks each | One actual Fabric client: 85-stage editor journey, saved definitions, named reward icon and custom bag-model checks pass |

All sixteen targets pass full dedicated progression, restart and independent-world scenarios: 3,018 checks across the preserved reports. These use simulated players with embedded connections. The three new GUI journeys each use one actual TCP client and are not multiplayer results. Historical 26.1.2 two-client evidence remains separate. Further GUI, JEI/HUD and real-pack review is still needed on untested combinations.

Successful reports are preserved under `runs/ports-20261113/dedicated/` and `runs/ports-20261113/gui/` (57 actual-game screenshots). The artifact manifest records SHA-256 checksums for the final copied JARs. These runtime/build artifacts are intentionally excluded from Git.

## Repeatable commands

Run with Windows Python and the configured Java 25:

```text
python build-smart.py compile:modern
python build-smart.py :26.1.2-fabric:packageTarget :26.1.2-neoforge:packageTarget
python scripts/verify-matrix-artifacts.py 26.1.2-fabric 26.1.2-neoforge
python scripts/test-server.py all --minecraft-version 26.1.2 --reset --offline
python scripts/test-bundled-examples.py all --minecraft-version 26.3
python build-smart.py smoke-server:26.3-fabric -Pcodex_server_smoke --offline
```

Package any other node by substituting its exact name. Artifacts live in `build/libs/<minecraft>-<loader>/`. Unqualified compile/build/matrix commands include all sixteen registered targets. All sixteen package successfully and pass metadata/class/resource inspection as of November 13. Do not publish these builds.

`-Pcodex_server_smoke` selects a disposable loopback/offline server under `runs/matrix-smoke/<target>`, with its own world, EULA and ephemeral port. Validation harnesses similarly isolate their worlds by full target name. Never point them at player saves.

## Compatibility decisions and remaining work

- DisplayInfo record accessors, client screen/HUD APIs, criterion packages, packet codecs and advancement-listener lifecycle are adapted only at their verified version boundaries.
- Generated criterion schemas differ in 26.2 and 26.3. `CriterionFormats` adapts generated definitions and read-only visual inspection; arbitrary raw JSON remains untouched. Entity/biome predicates are checked through native codecs to catch silently dropped targets.
- Bundled vanilla examples apply their presentation onto the running game's native advancement definition. Native criteria, requirements and rewards remain authoritative. Only unchanged installer-owned examples are upgraded automatically; authored content is retained and replaced owned files are backed up.
- Expanded 26.3 testing exposed recipe objectives now requiring reloadable recipe registries, and stricter validation of backgrounds on child advancements. Generated reward quantities also require the new native `modifier` field instead of `functions`; a loaded table can silently yield one item otherwise. The full dedicated simulated progression suite passes on both loaders after these adaptations. An actual 26.3 NeoForge client now passes the full 85-stage authoring journey; Fabric GUI parity and broader gameplay still need review.
- Legacy targets now use native GUI/input/rendering adapters, registries and datapack formats, item components/NBT boundaries, and loader initialization. No feature-disabling stubs or duplicated whole-mod source trees have been substituted for these ports. Minecraft 1.20.1 uses Architectury 9 split framing with bounded reassembly (32 MiB per message, 64 MiB total pending data), malformed-frame rejection, and disconnect cleanup.
- Optional actual-client test sources now use the same native Stonecutter preparation as production and dedicated scenarios. Their version-specific connection/input/screenshot APIs are being verified. Actual editor runs pass on 1.20.1 Fabric, 26.1.1 NeoForge and 26.3 NeoForge. This does not establish JEI/HUD parity on every target.
- Older cooking recipes produce one item per operation. The editor locks that quantity and server validation rejects larger counts rather than accepting JSON whose native codec silently changes the result. Crafting output quantities remain configurable.

## Early NeoForge binding exception

Minecraft 26.1 and 26.1.1 on NeoForge use four small native binding classes under `gradle/compat/early-neoforge`. Shared quest, progression, persistence, GUI and packet definitions are unchanged. The per-target `codex_native_platform` flag selects native lifecycle/player/tick/command events, screen opening and typed payload registration. These two artifacts do not require Architectury; the other fourteen retain the existing suite binding.

The published Architectury 20.0.2/20.0.4 NeoForge jars advertised for these versions reference `BreakBlockEvent`, absent from NeoForge26.1.0.19-beta/26.1.1.15-beta. API19.0.1 belongs to Minecraft1.21.11 and fails a ChunkMap mixin on26.1. No external mod jar is patched or republished. Adding an incompatible Architectury build for another mod can still prevent those older loader versions from starting.

Both native bindings pass the complete187-check simulated progression suite, including restart and independent worlds. Artifact inspection additionally rejects accidental Architectury linkage/dependencies on these two nodes.
