# Recipe editor validation — November 2, 2026

Implemented for Minecraft26.1.2 Fabric and NeoForge. No publication or commits.

- Both loader dedicated-server recipe suites:18 checks passed each. Native shaped/tag matching, shapeless order, output counts, four cooking serializers, preservation, invalid inputs, document hashes and bounded packet serialization. These use server recipe inputs, not real or simulated player gameplay.
- Fabric Mine MCP real GUI:10 authoring/save/reopen/vanilla-override/restore checks passed, plus5 cooking/invalid-save/concurrent-edit/custom-removal checks. Widget callbacks, text fields and framebuffer capture only; no OS mouse/keyboard injection.
- Both distribution JARs built with the repository smart wrapper; no test classes packaged.

Local evidence is under ignored `runs/recipe-validation` and `runs/mine-mcp-probe`. Dedicated suites are repeatable using `python scripts/test-recipes.py all --build-init runs/frame-mask-build.gradle` on Windows. The init script is the preexisting local build-directory workaround; omit it on an ordinary checkout. The test task uses disposable worlds and opt-in test sources.

The GUI walkthrough created `codex_probe:eye`, output2 Eyes of Ender from logs plus diamond. It temporarily changed `minecraft:ender_eye` output to3, then restored vanilla's output1 and retained a backup. Extra cooking tests used only disposable `codex_probe:cooking` and removed it after checking stale-save protection.

Not verified here: NeoForge GUI, two actual remote clients, actual furnace inventory consumption, fault-injected reload rollback, or every GUI scale. Recipes are validated/matched with native server codecs. The last malformed-document safeguard and advanced-type control guard were compiled/packaged after runtime checks; runtime checks exercised ordinary supported paths before those guards.
