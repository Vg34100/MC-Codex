# GUI and development-launch review — 2026-10-21

## What changed

The previous milestone left examples and compatibility jars confined to opt-in tests, and retained the old inline forms next to new dialogs. This follow-up fixes the normal development launch and replaces those duplicate forms.

- Both regular loader clients use shared `gradle/development.gradle`, unmodified local Sagittary/Sleep Cycle jars, and a one-time example seed. IntelliJ client configurations delegate to Gradle; previous Application launchers are backed up locally.
- Reward and criterion dialogs own private drafts, pickers, Save/Remove/Cancel. The sidebar contains entry lists. Item possession supports selecting existing item tags. Standalone item and claim screens also replace the older overlapping pickers.
- Edit mode retains the viewed tab, selects its root and reveals its tab button. Switching tabs applies the current draft and selects the new root. Compact action labels fit the default client size.
- Native older texture paths are resolved for rendering without rewriting advancement JSON. The window frame no longer covers Party/Rewards/Claim All controls; pending claim counts update live.

## Actual Fabric client evidence

Used the normal `:fabric:runClient` and a separate **Codex GUI Review** world at 854×480, GUI scale 2. These are actual client interactions with its integrated server, not simulated players.

| Check | Observed result |
| --- | --- |
| Default development setup | Logs list unmodified Sagittary 2.0.1 / Sleep Cycle 1.0.5 and both example packs. |
| Independent story | The Field Journal appears after entering the world. |
| Native advancement behavior | Obtaining crafting table, fletching table and bed unlocks their original roots. Both native mod tabs appear in normal view without overrides. |
| Legacy mod background | Sleep Cycle renders normal stone after the rendering-only fallback. |
| Existing native criterion | Bed-tag criterion opens; unchanged Save creates no Sleep Cycle override. |
| Rewards | Search for Bread, return with its icon/name, show validation and Cancel the draft. |
| Visual delivery authoring | Two icon rows, quantities 8/16, Add Item, remove, Apply/Cancel fit the default GUI. |
| Partial submission | Submit 4 logs + 8 cobblestone, then 4 + 8 again; world progress records exactly 8/16. |
| Random treasure / XP | Claim story treasure and XP. This run generated 7 bread. Repeated treasure click grants no second reward; ledger records each of its two slices once. |
| Saving / undo | Temporarily rename story supplies through GUI Save. Server file and client reload; criteria and parent match. Undo Apply + Save restores the original title. |
| Treasure editor | Field Provisions displays named rolls, ranges and weights; Save returns “Treasure list saved.” |

Screenshots are local ignored evidence under `runs/gui-review/`, including `fabric-native-tabs.png`, `fabric-sleepcycle-fixed.png`, `fabric-native-tag.png`, `fabric-delivery-editor.png`, `fabric-reward-item.png`, `fabric-treasure-saved.png` and the partial/completed delivery images. The header rendering fix was made after the Fabric pass and was checked in the final NeoForge pass.

## NeoForge final-code check

Used the normal `:neoforge:runClient` and a new **Codex GUI Review NeoForge** world.

- Both unmodified compatibility mods load; native crafting-table/fletching-table/bed criteria complete and the tabs appear. The Field Journal appears independently.
- Final Party/Rewards/Claim All controls are visible above the frame. The named claim picker shows **Stone in Your Hands**; claiming updates **R(1)** to **R(0)** and disables empty claim actions. The world ledger records both reward slices.
- Native Sleep Cycle background is correct. The final sidebar labels the objective **Obtain any beds**; its separate dialog retains the native bed tag.
- Resized from 854×480 (scale 2) to a 1280×800 outer window, 1264×761 client area (automatic scale 3). The native criterion dialog retains its draft and all Save/Remove/Cancel controls fit.
- No ERROR/Exception entries were found in this NeoForge run's log. The Fabric run includes an existing orphaned local `codex:test_delete_2` authoring entry and offline authentication/Realms errors; neither was silently removed or presented as a clean log.
- Both review clients were closed normally and their worlds saved. Desktop input has ended. Final compact-button tooltip additions were packaged after the visual pass.

NeoForge screenshots: `neoforge-final-header.png`, `neoforge-claim-picker.png`, `neoforge-claim-count-updated.png`, `neoforge-native-editor.png`, `neoforge-dialog-large.png` under `runs/gui-review/`.

## Build and scope

Both 26.1.2 distribution jars build and pass ZIP/class checks. Test classes, example resources and other mod jars are excluded. Three installer tests pass. The earlier two-simulated-player dedicated suites passed 73 checks per loader; they were not rerun for these client-only changes.

This is not two-real-client or real dedicated transport/permission coverage. Full multi-scale interaction, every advanced predicate/group editor and party GUI remain broader follow-up checks. The main canvas/sidebar still uses minimum widths totaling about 422 logical pixels; narrower GUI layouts need additional work. A party-overlay input guard was compiled after this visual review. Dialogue choices, repeat cycles, physical bags, tracking HUD, crash recovery journals and additional Minecraft targets remain unfinished.

Desktop automation is permitted for this review only after the user's explicit warning/authorization. The helper targets one Minecraft process and rejects off-window coordinates or another foreground application. Warn before any future desktop-control period.

## Quick visual evidence

- [Final editor and native criterion list](../../runs/gui-review/neoforge-native-editor.png)
- [Reward dialog with selected item](../../runs/gui-review/fabric-reward-item.png)
- [Visual delivery editor](../../runs/gui-review/fabric-delivery-editor.png)
- [Treasure editor save acknowledgment](../../runs/gui-review/fabric-treasure-saved.png)
- [Larger criterion dialog](../../runs/gui-review/neoforge-dialog-large.png)

Final local artifacts: `fabric/build/libs/codex-fabric-26.1.2-0.1.0.jar` (291899 bytes) and `neoforge/build/libs/codex-neoforge-26.1.2-0.1.0.jar` (292205 bytes). Final build and ZIP/release-exclusion checks pass. No commits or publishing occurred.

## Continuing implementation: scaling and quest framework

The new [walkthrough](REVIEW_WALKTHROUGH.md) and [actual-game gallery](REVIEW_GALLERY.md) cover the continuing milestone.

- Fabric, fresh **Codex Camp Chronicle Review** world, 1264×761 client window: native GUI scale3, Comfortable Codex scale2; returning to game/native settings restored scale3. Full-height canvas/sidebar and all nested editors reviewed. Explicit native-scale comparison also exercised.
- Actual story choice saved; the review kit unlocked native Sagittary/Sleep Cycle through their original inventory criteria. A held Camp Satchel consumed1 and generated bread3/torch5. Two delivery cycles consumed4oak/8stone each, granted per-cycle XP/random loot/bag, and retained the original advancement completion. Five real Space-key holds completed the statistic trial and updated the optional two-quest HUD.
- Actual dialogue edit → Apply to Draft → Save reached quests.json. Native root JSON retained ordinary tick criteria and contained no Codex wire fields. Undo Apply → Save restored the original story. Reviewed branch selection, repeat rules, bag reward modal, delivery rows/picker and treasure rolls/weights/ranges. Drafts were cancelled after inspection.
- Readable human reward labels/icons replaced the first review's raw-ID previews. The final gallery identifies which earlier image only records cycle behavior.
- NeoForge, our preexisting **Codex GUI Review NeoForge** save: new demo seeded without replacing existing edits; native tabs, new quest details, bag/item models, normal claim and physical bag opening passed. One bag became torch11 plus2bread; runtime log had0 ERROR/FATAL entries. Party overlay correctly dimmed/disabled underlying widgets; no invitations were sent.
- Development launch now supplies stable `--username=CodexDeveloper`, configurable with -Pcodex_dev_username. The earlier two Fabric launches used different random Player### identities, so no same-identity real-client restart assertion is made from them. Dedicated scenarios use fixed UUIDs and verify reconnect/restart separately.
- Both owned clients closed normally; original options.txt and codex-client preferences restored for both loaders. Only isolated review saves were used.
- Final server suites: Fabric80 initial +13 restart +9 independent; NeoForge80+13+9, two embedded SIMULATED players and unmodified Sagittary/Sleep Cycle. Final compile/shadowJar and distribution ZIP checks passed. Recovery boundaries are tested scenarios, not a literal process-kill test.

## Follow-up: actions, dialogue outcomes and scheduled shared cycles

- Fabric actual window1264×761, native scale3/Comfortable2: opening Stretch Your Legs after root hover worked; a0.7s hold accepted it without opening details. Right-click submission before acceptance showed an accept-first toast. A controlled2-log partial delivery produced an exact receipt and2/4 progress.
- Saved campkeeper choice showed outcome text and Open Chapter navigated to A Place to Return To. Choice editor, chapter picker, daily and shared rule screens were reviewed. Save Choice → Apply to Draft → main Save persisted next_quest; parsed native root JSON stayed equal to its before-image.
- NeoForge actual owned Codex GUI Review NeoForge world: unmodified companion tabs appeared normally; untouched Field Journal was backed up/retired, leaving a single8-node Chronicle demo. Hold acceptance and tree submission consumed1 stick; toast and detail showed1 delivered, tooltip1 remaining. UI switches, native-scale3 rule layout, contextual hiding, translated chapter labels/search were reviewed. A temporary disabled-repeat/new-choice draft was discarded; persisted Daily rule remained enabled.
- Final chapter-picker display filtering is compile/package verified after the review; it was not separately launched again.
- Both owned clients closed normally. Original options/config restored and verified byte-for-byte, including absence of a file originally missing. User dev/test worlds were not entered.
- NeoForge runtime log0 ERROR/FATAL. Fabric retains the pre-existing orphan codex:test_delete_2 (5 logged load errors) and3 offline authentication/Realms errors. This unrelated authored entry was preserved.
- Final server results on EACH loader:110 initial +15 restart +9 independent =134, with embedded SIMULATED connections. New regressions include exact receipts despite a native function refund, repeat tooltip snapshots/reset, shared two-member consumption/claims/late joins/leave-rejoin, shared statistics, daily/periodic boundaries, native partial dialogue stages, and conservative legacy-demo consolidation. Earlier inventory/recovery/bag/vanilla compatibility tests still pass.
- Both final distribution jars compile/package; common/client classes, bag assets, mixins and loader metadata present; examples, test classes and companion mods excluded. Three Python installer checks and diff --check pass. No new Minecraft version, real two-client dedicated transport test, or literal process-kill test is claimed.
