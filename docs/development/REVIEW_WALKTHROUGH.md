# In-game review walkthrough

[Actual in-game screenshots](REVIEW_GALLERY.md) · [Recovery details](RECOVERY.md) · [Recreate every demo in the GUI](GUI_AUTHORING_RECIPES.md)

Use **Minecraft Client (:fabric)** / **(:neoforge)** delegated to Gradle, or the matching Gradle `runClient`. The dev launch loads unmodified local Sagittary and Sleep Cycle jars and seeds editable examples without replacing authored changes. Fresh launches use one story demo, the Camp Chronicle. An untouched legacy Field Journal is backed up in `codex/development_example_backups/field-journal-v1` and retired; edited copies and their children remain, labelled “Legacy Demo” if their root title has not been customized. Production jars do not contain those mods or examples. Existing authored edits and deletions are preserved across launches.

The demo now uses the stable offline name `CodexDeveloper`; `-Pcodex_dev_username=YourName` selects another stable review identity. Old random test profiles are retained. Create a new Creative review world. Open Advancements (default L). In Codex's header, **UI** opens scale/tracker settings. **Comfortable** is the default: Minecraft Large uses the medium-sized coordinate scale inside Codex. Leaving Codex restores normal Minecraft scale. The canvas and sidebar remain together. Resize and try Minecraft's large GUI setting; use the settings to compare with **Minecraft** or a specific scale.

## Tabs and examples

- **Minecraft: Field Notes**: vanilla Story root; **Stone in Your Hands**, **A Stronger Foundation**, **The First Iron** retain their original objectives. Stone in Your Hands gives claimable XP/bread. Click stone or iron to read their optional narrative pages.
- **Adventure: Beyond the Campfire**: the vanilla Adventure tree with extra introductory description/dialogue.
- **The Field Journal**: the earlier five-node example using obtaining, delivery, exploration, crafting and the editable Field Provisions random treasure pool.
- **The Camp Chronicle**: the new independent story tab. Its book root completes automatically. Click any node for quest details. The story and contracts work anywhere, without an NPC or a particular location.
- **Sagittary** and **Sleep Cycle** use their original visibility/objectives. Obtain a **fletching table** and a **bed** to unlock their native tabs. The review kit below supplies both. Edit Mode shows their complete trees immediately, including unearned nodes. No native mod advancement JSON is replaced by the showcase.

## Walkthrough

1. Open **The Camp Chronicle → A Small Head Start** and **Claim Rewards**. The kit contains 16 oak logs, 32 cobblestone, a Camp Satchel, a fletching table and a black bed. Briefly close Codex and reopen it so the original native inventory triggers run; the two mod tabs should unlock.
2. Click the Chronicle's book root, then **Story Choices…**. Select campkeeper or wanderer. The saved choice unlocks that branch's native criteria, acceptance and claims. The opposite branch is dimmed and cannot earn criteria or claim rewards. It does not restrict ordinary Minecraft play. Try the opposite branch to see its locked status. Choices are personal and cannot be changed by replaying the button.
3. Open **Keeping the Camp Supplied**. **Accept Quest**, then **Pin to Tracker**. Close Codex to see its compact optional HUD. Reopen and **Submit Items**: 4 oak logs and 8 cobblestone are consumed. Partial deliveries are saved when you have fewer items.
4. Claim this contract's XP, direct random Field Provisions and Camp Satchel. A second click cannot duplicate them. After 30 seconds, **Start Next Cycle**. Its delivery counts reset, while the native advancement stays completed. Each player has their own cycle, even in a party; normal vanilla party sharing continues.
5. Hold the **Camp Satchel** and right-click. One bag is consumed and the server rolls its pool. The same pool supplies the direct random reward. A missing pool does not consume the bag. A full inventory stores overflow; quest details offer **Collect Stored Rewards** when needed.
6. Open **Stretch Your Legs**, accept it, then jump five times. Jumps before acceptance do not count. Pin it to watch the delta. Claim its torches; after 15 seconds another cycle is available.
7. Try the chosen native branch: obtain a crafting table for **A Place to Return To**, or enter any forest for **Let the Trees Be Your Roof**. These keep ordinary inventory/location triggers.
8. Enable **Edit Mode**, select a Chronicle node, scroll the sidebar to **Quest Story & Rules**. Edit introduction/progress/completion pages; edit choice labels and follow-up text; configure acceptance, repeatability/cooldown, or select a required story choice on the Branch page. **Apply to Draft** keeps it local, **Cancel** discards it. Back in the advancement editor, **Save** writes and reloads the datapack. **Undo Apply** restores the previous applied draft.
9. Select the supply quest's delivery criterion: item rows show icons/names/counts; **Add Item** opens searchable items. Select a reward: its separate dialog owns item/pool selection, quantity, Save/Remove/Cancel. Treasure Bag adds display name and item-model appearance. No duplicate reward form lives in the sidebar.
10. Open **Treasure Lists** to inspect Field Provisions: two weighted rolls, quantity ranges, bread/torch/iron entries. Change a weight or add an item, save, then open another satchel. Roll generation remains on the server.
11. Open native Sagittary/Sleep Cycle nodes in Edit Mode. Resaving an unchanged definition preserves its criteria, requirements and unknown fields. Add story text or rewards using the same editor when you want an authored override.

## Recovery and current limits

Recovery is verified with server integration fault-boundary scenarios, not a GUI animation. The prepared journal records inventory outcomes and sidecar updates. A receipt inside vanilla player data proves whether inventory/XP saved; recovery finishes committed sidecars without rerolling/regranting, or aborts an unsaved action. Existing index claim files remain readable; stable reward IDs prevent reorder duplication. Files keep backups. Arbitrary functions interrupted after starting require operator review in `codex/reward_effects`; their world effects cannot be replayed safely.

Repeatable cycles support delivery/statistic tasks, personal or shared party cycles, cooldowns, UTC daily availability and aligned periodic intervals. Shared cycles award one group reward set; late members wait for the next cycle. Unfinished work and unclaimed rewards remain until explicitly starting another cycle. Dialogue supports saved outcomes, stage conditions and chapter navigation. Delivery tag/component predicates, arbitrary conditional dialogue graphs, per-participant party-cycle entitlements, richer contribution displays and additional Minecraft versions remain future work. Native advancement progress is never globally revoked.

## Next-round review

- Click **Stretch Your Legs** after hovering another node: the node opens its own detail page. A slow/missing response exposes Retry Loading. Hold an eligible node for0.7s to accept directly; move the mouse to cancel the gesture and pan. UI settings can disable hold acceptance and hover animation.
- Submit before acceptance using the tree shortcut: the toast says to accept first; nothing is consumed. Accept, submit a partial quantity, then inspect the toast and quest receipt: they name each consumed item and quantity. The inventory and remaining delivery agree.
- Open **Story Choices** on the root. Choose a chapter, read the saved outcome and use **Open chapter**. Reopening shows the result. The selection affects the destination’s Branch rule; it does not grant rewards or block vanilla progression.
- **A Little Kindling** demonstrates the daily personal contract. **For Everyone at Camp** demonstrates shared party progress and one shared reward chest; it is also playable solo.
- Story & Rules → Rules exposes schedule, duration and scope. Choices exposes stage and linked chapter pickers. Every demo recipe is documented in GUI_AUTHORING_RECIPES.md.

## October 22 feedback review

The latest changes use one visible icon renderer for the tree and hover card. There is no universal click/hold footer. Hover descriptions explain relevant actions; item and reward chips retain input priority over covered background nodes. Move outside a card to reach a node it covers.

Use A Little Kindling to accept with a green sweep, submit one stick at a time, see the remaining quantity update, then watch the frame turn yellow immediately on the second delivery. Each acknowledged delivery produces a translucent receipt with the exact consumed items. Claim XP and bread from their gold-dotted reward icons. The repeat dot remains after claiming. A Small Head Start is a one-time example: after all its rewards are claimed, it remains plain yellow.

Use the Chronicle root to choose campkeeper. The crafting-table branch becomes available; the sapling branch dims and its hover explains the exclusion. Its native trigger is now gated as well as its Codex actions. This affects only quests with explicit Branch rules.

`/advancement revoke @s everything` now clears native criteria, Codex delivery totals, acceptance/cycles, story choices and both reward ledgers. Shared party cycles and shared delivery totals reset for the group; other members' personal story/claims remain. Inventory, XP, mailbox rewards, statistics and party membership remain. Tick/inventory/location criteria may naturally earn again after the reset. Ordinary narrower revoke commands retain their native semantics. The party's stored native sharing ledger is cleared too; teammates who were not targeted can still intentionally re-share native criteria they retain. Target all members (for example `@a`) for a full party reset.

Before resetting, Codex copies affected JSON to this world's `codex/reset_backups/<timestamp>-<id>/`. Reset writes use the existing receipt journal. These backups are retained; clean them manually when no longer needed. An administrator reset intentionally permits legitimately earning/claiming the quest again; it does not recover already granted items from players.
