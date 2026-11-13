# Bundled examples validation — November 3, 2026

The release selection now consists of six native vanilla advancement presentations and twelve Camp Chronicle quests, packaged from `examples/bundled`. Normal IDE and production entrypoints use that same installer; companion mods remain external.

## Automated dedicated servers

`python scripts/test-bundled-examples.py all --build-init runs/frame-mask-build.gradle` passed25 assertions per loader, Fabric26.1.2 and NeoForge26.1.2. These are server resource/installation checks, not two actual player clients.

Checks cover native loading, all12 quests, exact vanilla criteria/requirements/parents/rewards after excluding display, no recipe or legacy tree, sidecar merging, restart idempotence, author deletion, switch disable/re-enable, backups, preserving pre-existing data/customized groups/dependencies, and aborting removal on malformed authored JSON. Reports: `runs/bundled-validation/<loader>/results.json`.

Both shadowJars built. Every curated JSON resource inside each JAR was compared with its source; no test classes or companion-mod JARs were included. The final bottom-row spacing adjustment was resource-only and received packaging and actual-game review.

## Packaged Fabric game

A standalone production-mode KnotClient launched the actual distribution JAR from `mods`, with no development project outputs on the classpath and `fabric.development=false`. FabricAPI/Architectury provide dependencies; Mine MCP provides authenticated local widget/framebuffer testing. The isolated profile uses Loader0.19.5 for Mine MCP; the normal mod dependency pin was not changed.

Six checks passed: six vanilla files, twelve story definitions, no missing-mod companion sidecars, no recipe changes or legacy tree, and the real editor opening with operator permission. The initial overlong test username was corrected before successful connection.

Four reward checks passed: expected starter items in the actual player inventory, Claim All disabled after collection, inventory remained stable, and using the physical Camp Satchel consumed it and produced treasure. This GUI check does not independently prove rejection of forged duplicate-claim packets; existing framework server tests cover claim guards.

All interaction used Mine MCP widgets/player actions, never desktop pointer/keyboard injection. Source world was a clone of a disposable test-server world, excluding player state and old datapacks. The normal user's worlds and other clients were untouched. Evidence and actual framebuffer screenshots are under ignored `runs/bundled-review`.

Not verified here: packaged NeoForge GUI, new two-real-client multiplayer run, fault-injected installer interruption or every resolution. The installation ledger protects ordinary edits/restarts; it is not a transactional multi-file database. Malformed config is logged and leaves authored content unchanged.

Before public release, finish the ordinary recipe-inventory/NeoForge GUI checks and prepare reviewed current screenshots. Nothing has been uploaded, committed or published.
