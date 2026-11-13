# Review images from the actual game

**Latest creator round:** [GUI-authored branches, creator tools, tag deliveries, shared rewards and milestone effects](CREATOR_REVIEW_2026-11-01.md), with actual-client/restart/migration evidence.

**Previous:** [Repeated navigation, saved choices and narrow tooltip fixes](NAVIGATION_REVIEW_2026-10-22.md), including actual NeoForge stress-review screenshots. The final advancement dots form one adjacent horizontal row at the upper-right. [Earlier local feedback review](JUICE_REVIEW_2026-10-22.md); older screenshots below retain earlier layouts.

## Latest round: clear actions, dialogue outcomes and scheduled party quests

These are actual Fabric26.1.2 window captures at1264×761, Minecraft GUI scale3 and Codex Comfortable scale2.

![Exact two-log delivery receipt and native toast](../../runs/gui-review/next-partial-delivery.png)

The two submitted logs are named in both the toast and quest receipt. Remaining objectives show2/4 logs and0/8 cobblestone.

![A saved choice and an explicit button to open its unlocked branch](../../runs/gui-review/next-choice-outcome.png)

Choosing the campkeeper chapter now shows the narrative outcome and a working Open Chapter button. Reopening shows the saved decision.

![Daily UTC scheduling in the visual rules editor](../../runs/gui-review/next-daily-rules.png)

![Shared party rules clearly explain one group reward set](../../runs/gui-review/next-party-rules.png)

![Visual choice editor with timing, chapter picker and outcome text](../../runs/gui-review/next-choice-editor.png)

The chapter picker was used to add a real link, then Save Choice → Apply to Draft → Save wrote the sidecar. The native root JSON remained equal as parsed.

![Holding a node fills an acceptance bar](../../runs/gui-review/next-hold-accept.png)

![Hold acceptance stays in the tree and confirms the action with a toast](../../runs/gui-review/next-hold-result.png)

![Submitting before acceptance explains why no items were consumed](../../runs/gui-review/next-accept-first.png)

[How to recreate all eight demo quests](GUI_AUTHORING_RECIPES.md).

## Final NeoForge checks

![One Chronicle story tab alongside native Sagittary, Sleep Cycle and vanilla advancements](../../runs/gui-review/next-neo-chronicle.png)

The untouched legacy Field Journal was backed up and retired. Edited copies on other launch configurations remain and receive a legacy label on restart.

![NeoForge tree tooltip and one-stick delivery toast](../../runs/gui-review/next-neo-delivery-tooltip.png)

![NeoForge saved daily progress agrees with the tooltip](../../runs/gui-review/next-neo-daily-progress.png)

![Independent animation and hold settings](../../runs/gui-review/next-ui-settings.png)

![Rules fit at explicit native GUI scale3](../../runs/gui-review/next-neo-native-rules.png)

![Repeat-only controls disappear when repeatability is disabled](../../runs/gui-review/next-neo-contextual-rules.png)

![Native advancement chapter names use readable localized titles](../../runs/gui-review/next-translated-chapter-picker.png)

The final picker additionally filters out advancements without a display, such as invisible recipe unlock entries. This last filtering change was compiled/packaged after closing the review client.

## Earlier verified milestone

These are captured Minecraft windows, not mockups. The [walkthrough](REVIEW_WALKTHROUGH.md) names the playable examples and exact controls. Screenshots live in the ignored local `runs/gui-review` evidence directory; they are not bundled in the mod.

## Comfortable scale and the combined editor

Minecraft is set to GUI scale 3 (Large); Codex uses scale 2. The editor keeps its canvas/sidebar together and uses available height. This image edits unmodified native Sagittary advancement criteria.

![Editor at Comfortable scale](../../runs/gui-review/camp-editor-final.png)

![Codex settings](../../runs/gui-review/camp-settings.png)

## Quest presentation and reward previews

![Optional narrative, acceptance, task counts and readable reward icons](../../runs/gui-review/camp-quest-details-final.png)

## Dialogue and branches

![Introduction/progress/completion editor](../../runs/gui-review/camp-dialogue-editor.png)

![Required story choice selected graphically](../../runs/gui-review/camp-branch-editor.png)

![Player's story choice menu](../../runs/gui-review/camp-story-choices.png)

## Personal repeat cycles

![Acceptance, repeatability and cooldown editor](../../runs/gui-review/camp-repeat-rules-editor.png)

The next image was captured before the final readable reward-preview refinement. It records a real second delivery cycle with counts reset; the underlying vanilla advancement remained completed.

![Second personal cycle](../../runs/gui-review/camp-second-cycle.png)

## Visual item and treasure editors

![Delivery rows with item icons and quantities](../../runs/gui-review/camp-delivery-items-editor.png)

![Searchable item choices](../../runs/gui-review/camp-item-picker.png)

![Weighted treasure pool with ranges and rolls](../../runs/gui-review/camp-treasure-weights.png)

![Bag reward dialog owns pool, quantity, name, appearance and Save/Remove/Cancel](../../runs/gui-review/camp-bag-reward-editor.png)

## Gameplay HUD and physical bags

The Fabric kit satchel was consumed through normal item use and produced bread/torches. The optional tracker shows the accepted delivery's progress at native game HUD scale.

![Actual bag outcome and tracker](../../runs/gui-review/camp-bag-opened-hud.png)

![Two tracked quests; five real jumps completed the statistic trial](../../runs/gui-review/camp-stat-hud-complete.png)

## NeoForge

![NeoForge story and native mod tabs](../../runs/gui-review/camp-neoforge-tree.png)

![NeoForge item/bag reward rendering](../../runs/gui-review/camp-neoforge-rewards.png)

![NeoForge satchel consumed and loot generated](../../runs/gui-review/camp-neoforge-bag-opened.png)
