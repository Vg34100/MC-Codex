# Playtest validation — November3,2026

## Installed artifact

New Prism instance: `C:/Users/video/AppData/Roaming/PrismLauncher/CodexSurvival-26.1.2-Fabric`, display name **Codex Survival - Playtest**.

Minecraft26.1.2 / Fabric Loader0.19.5 / Java25,4GB heap.22 top-level JARs, including libraries. All hashes match `mods.lock.json`. Exactly80 display advancements plus7 invisible recipe-book unlock advancements. No saves, test-player progress, MineMCP control helper, accounts or global Prism modifications. Existing instances untouched. A private import archive is under ignored `runs/survival-pack/CodexSurvival-26.1.2-Fabric-prism.zip`.

## Actual-game checks

The locked full mod set launched in an isolated production-mode Fabric client from distribution JARs, not project output directories. An authenticated local test helper exercised real game widgets and normal player/container actions. No desktop pointer or keyboard injection was used.

- Codex editor opened successfully with the test player's actual permissions.
- Pack-wide Creator Tools diagnostics reported no invalid definitions or links.
- All80 quest titles were present in the actual server authoring snapshot, including deep nodes not initially visible under normal vanilla advancement rules.
- A real Survival crafting table matched the changed Cold Eye recipe:6 packed ice,2 tin ingots,1 Ender Pearl.
- Taking its result consumed all9 ingredients and produced exactly1 Cold Eye in the player's inventory.
- Final22-mod startup fixed the earlier missing ClothConfig/AppleSkin settings error.
- Final source reload produced no advancement/custom-recipe parsing errors and the GUI remained operational.
- Survival Star requires a custom-data marker as well as its name, so an ordinary anvil rename is not its defined completion predicate. This marker check was source validation; a full end-to-end final-star crafting/dragon playthrough was not performed.

Reports/screenshots are under ignored `runs/survival-pack`: `editor-results.json`, `craft-results.json`, `final-smoke-results.json`, `pack-editor.png`, `pack-play.png`, `prism-installation.json`.

The test helper initially referenced two pre26.1.2 names (`ClickType`, `handleInventoryMouseClick`). A local test-only copy was patched to the inspected mapped APIs (`ContainerInput`, `handleContainerInput`) before the successful crafting test. Neither the original nor patched helper is installed in Prism. It is not a Codex/gameplay change.

The helper's offline test account generated authentication/Realms log noise. The installed Prism instance uses the user's normal launcher account selection and does not copy offline credentials or account files.

## Remaining playtest work

No complete survival playthrough, balance validation, two-player run, dedicated-server test of this exact third-party mod list, full foundry-production chain or physical twelve-eye portal activation was performed. The existing Codex framework has separate multiplayer tests; those do not substitute for testing this complete pack.

Known selected-mod warnings are documented in README. No assertion is made that all third-party warnings are harmless in every gameplay situation. Verify foundry progression, fishing species access, native eye acquisition, portal opening and final trophy in survival before a public release.

This task changed pack sources/tooling/docs only. Codex's production Java and its small bundled examples were not expanded with the campaign. Nothing was committed, pushed or published.

## November 4 presentation update

The generator still produces80 displayed quests,69 with completion notifications,8 quiet chapter introductions and3 deliveries with submission receipts. All8 roots explicitly enable quest-tab styling. Delivery spacing and technical explanations were replaced with gameplay wording. Matching installed descriptions/dialogue were patched without replacing different author edits or touching saves, criteria, rewards or progression.

Both loader distribution JARs build successfully. The dedicated initial suites pass162 scenarios per loader with two **simulated** players; new checks cover explicit tab styling, hidden-node exclusion, incomplete descendants, completed tabs with unclaimed rewards, clearing dots on claim, acceptance dots and refresh packet delivery. Three Python tests pass. No test classes or JEI API classes are bundled in the release JARs.

The private original Prism import ZIP predates these fixes and must be regenerated before reusing it.

Final isolated Fabric GUI run passed18 checks: summaries for all8 chapters, Claim All receipt, individual claim receipt, foreground Eye hit testing, JEI screen/ingredient registration, actual configured recipe-key routing opening the Cold Eye recipe, and chapter completion summary. Actual screenshots were inspected. The probe uses widget callbacks and game-local cursor coordinates, without OS input; only its owned client was closed. NeoForge GUI/JEI was not exercised; its build and dedicated simulated-player suite passed.

The final tested Codex JAR was installed into the existing Codex Survival Prism instance with an old-JAR backup under ignored `runs/survival-pack/prism-update-backups`. All22 installed mod hashes match the updated source lock; no test helpers were installed. Saves and authoring definitions were preserved. Restart Minecraft to use the updated UI. This update does not establish full survival balance or exact-pack multiplayer playthrough coverage.
