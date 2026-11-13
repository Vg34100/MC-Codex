# Stonecutter migration — November 7, 2026

The shared suite build is adopted from the existing local suite implementation. Sources remain canonical under common/main, common/client and the loader trees; native preparation applies narrowly scoped API adapters per target. Registering a node does not establish support.

| Minecraft | Loaders | Compile / JAR inspection | Dedicated evidence | Actual GUI evidence |
| --- | --- | --- | --- | --- |
| 26.1.2 | Fabric, NeoForge | Pass | 169 initial + 11 restart + 7 independent-world checks each | Existing two-client profiles; latest styled production-pack probe on Fabric |
| 26.1, 26.1.1 | Fabric, NeoForge | Pass | Not yet checked | Not yet checked |
| 26.2 | Fabric, NeoForge | Pass | Bundled install/native definitions: 29 checks each | Not yet checked |
| 26.3 | Fabric, NeoForge | Pass | Startup, 31 bundled checks and 169 initial + 11 restart + 7 independent-world progression checks each | Not yet checked |
| 1.21, 1.21.1 | Fabric, NeoForge | Registered, API ports unfinished | Not checked | Not checked |
| 1.20.1 | Fabric, Forge | Registered, Forge bootstrap/API ports unfinished | Not checked | Not checked |

The dedicated scenarios use simulated players with embedded connections, not two actual clients. Startup and bundled loading alone do not establish gameplay support. Newer production artifacts should remain development builds until editor, networking and player journeys have runtime coverage.

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

Package any other node by substituting its exact name. Artifacts live in `build/libs/<minecraft>-<loader>/`. Unqualified compile/build/matrix commands include all sixteen registered targets and currently fail on unfinished legacy ports. Do not publish these builds.

`-Pcodex_server_smoke` selects a disposable loopback/offline server under `runs/matrix-smoke/<target>`, with its own world, EULA and ephemeral port. Validation harnesses similarly isolate their worlds by full target name. Never point them at player saves.

## Compatibility decisions and remaining work

- DisplayInfo record accessors, client screen/HUD APIs, criterion packages, packet codecs and advancement-listener lifecycle are adapted only at their verified version boundaries.
- Generated criterion schemas differ in 26.2 and 26.3. `CriterionFormats` adapts generated definitions and read-only visual inspection; arbitrary raw JSON remains untouched. Entity/biome predicates are checked through native codecs to catch silently dropped targets.
- Bundled vanilla examples apply their presentation onto the running game's native advancement definition. Native criteria, requirements and rewards remain authoritative. Only unchanged installer-owned examples are upgraded automatically; authored content is retained and replaced owned files are backed up.
- Expanded 26.3 testing exposed recipe objectives now requiring reloadable recipe registries, and stricter validation of backgrounds on child advancements. Generated reward quantities also require the new native `modifier` field instead of `functions`; a loaded table can silently yield one item otherwise. The full dedicated simulated progression suite passes on both loaders after these adaptations. Actual GUI journeys remain unverified on these newer targets.
- Legacy ports need native GUI/input/rendering, networking, registries/data schemas and loader initialization work. No feature-disabling stubs or duplicated whole-mod source trees have been substituted for these ports.
- Optional actual-client test-source preparation needs extending to newer targets. No newer-version editor/JEI/HUD runtime claim is made from compilation.
