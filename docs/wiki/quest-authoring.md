# Advancement and quest authoring

## Ordinary advancements are the source

Codex reads the actual JSON resources for vanilla, datapack and mod advancements. The editor retains unknown fields, rich display components and arbitrary AND-of-OR requirement groups. Supported simple criteria have graphical controls; complex expressions open as raw criterion JSON. Editing presentation leaves their conditions intact.

Operators can use `/codex advancements edit`, or open Advancements and enable Edit Mode. Select a node, edit it, then **Save**. **Apply** updates the local draft without sending it to the server. Saved changes take effect after the server confirms its resource reload. **Undo Apply** restores a prior applied draft; closing with pending changes asks before discarding them.

The sidebar lists rewards and criteria. Click a row to edit it, or **Add Reward / Add Criterion** to create one. Each dialog owns its type-specific inputs, searchable pickers, Save, Remove and Cancel; there is no second inline entry form. Picking an item only changes that dialog's draft. Cancelling the dialog discards it. An existing item-possession tag can be preserved or selected with **Choose Item Tag**.

The existing canvas supports pan/zoom, resizable layout, custom positions and parent links. Parent links organize vanilla advancement trees; they do not inherently gate completion. Use vanilla criterion predicates and requirement groups for actual progression conditions.

## Items and statistics

For **Submit Items**, open **Edit Item List**, click **Add Item**, search by item name or identifier, and choose an icon. Change quantities and add/remove rows; Apply returns to the criterion draft. Deliveries consume matching items and save partial progress. Use Add Tag for searchable item tags and Props for exact custom names, potion types, damage or held-item components. Overlapping rows share a server-computed inventory allocation so each physical item is consumed once. Advanced component JSON remains available.

For **Statistic Threshold**, use **Configure Statistic**: select the category, target and required total. Item categories use the visual item picker; blocks, entities and custom statistics use searchable registry choices. Statistics use the player's cumulative Minecraft totals. Crafting also has an ordinary vanilla **Craft a Recipe** trigger.

New tabs/nodes offer templates for obtaining items, deliveries, defeating entities, exploring biomes/dimensions, crafting, statistic milestones, several objectives, and a blank custom quest. Templates produce editable advancement definitions. The several-objectives template requires its objectives together; it does not enforce temporal order.

## Requirements and advanced fields

**Edit Requirement Groups** exposes vanilla's logic: every group is required, and any checked criterion satisfies a group. Mixed groups survive ordinary Apply/Save without becoming ALL or ANY. New criteria become required groups when maintaining a custom expression; removing criteria removes their references. Explicitly selecting ALL or ANY intentionally replaces the grouping.

**Advanced JSON** edits the full document. The server validates before writing. It remains the escape hatch for unsupported triggers, predicates and fields. A top-level JSON `null` explicitly removes that field when applying a patch. Deleting a Codex override restores the underlying mod/vanilla definition; it cannot remove a resource inside another mod's JAR.

## Treasure lists and rewards

In Edit Mode, select a node and open **Treasure Lists**. Create or select a named list, choose its number of rolls, then add items or existing loot tables. Item entries expose minimum/maximum quantities and weight; a higher weight increases selection probability within each roll. Save and wait for server confirmation.

Add a **Random Treasure** reward to an advancement, choose its generated loot table with the picker, then Apply/Save. Existing vanilla loot tables are also selectable. Leave **Claim rewards manually** unchecked for ordinary vanilla completion rewards; enable it for explicit per-player claims. Treasure lists generate native `advancement_reward` loot tables, so the server owns all randomness and vanilla handles item delivery. Circular Codex treasure-list references are rejected. Referenced tables must support the context in which they are used; context-specific block/fishing tables may need their original parameters.

The optional story example includes **Field Provisions**, an editable weighted pool with bread, torches and iron. These are examples, not fixed gameplay rules. Physical treasure bags use one registered item with configurable pool, name and appearance components; see below.

## Optional example content

### Included showcase and development clients

The downloaded mod and regular IDE clients now use the same **six vanilla enhancements and twelve-quest Camp Chronicle** selection. See [Included examples](included-examples.md) for the exact list, configuration, ownership protection and fresh-install behavior. No manual installer is needed.

Regular IntelliJ/Gradle client configurations may additionally load local unmodified Sagittary and Sleep Cycle companion JARs. Those JARs are not shipped with Codex. Their native tabs keep their original visibility rules; optional narrative sidecars are installed only when the corresponding mod is present. Disable development companion loading with `-Pcodex_dev_demo=false`; this does not disable bundled content. Use `config/codex-examples.json` for that.

### Larger legacy development examples

The following table and manual installer describe the larger development packs under `examples/vanilla-enhanced` and `examples/framework-showcase`, not the curated release selection. Existing development installations keep their authored copies. Use a fresh installation when reviewing what a new download contains.

The visible examples are:

| Advancement | Example title / behavior |
| --- | --- |
| `minecraft:story/root` | **Minecraft: Field Notes** — expanded introduction; original crafting-table objective. |
| `minecraft:story/mine_stone` | **Stone in Your Hands** — narrative and manually claimable XP/bread. |
| `minecraft:story/upgrade_tools` | **A Stronger Foundation** — expanded vanilla tool progression. |
| `minecraft:story/smelt_iron` | **The First Iron** — expanded vanilla iron progression. |
| `minecraft:adventure/root` | **Adventure: Beyond the Campfire** — exploration introduction. |
| `codex_demo:camp_chronicle/root` | **The Camp Chronicle** — independent story tab with exclusive choices, delivery/statistic cycles, treasure/bags and conditional chapters. |
| Other vanilla nodes | The full 16-node Story tree plus Nether/End milestones and planting a seed: 24 vanilla definitions total. |

Six optional sidecars add dialogue to native Sagittary/Sleep Cycle milestones without replacing their advancement JSON. Existing overrides can retain earlier names because installation preserves your edits. Untouched legacy Field Journal content is retired with a backup; edited legacy copies remain. The story root appears automatically after entering a world. Native mod tabs retain native visibility rules: obtain a **fletching table** for Sagittary and **any bed** for Sleep Cycle. Edit Mode exposes their complete native trees without completing them or modifying their definitions.

### Installing examples elsewhere

Stop the server and install either example into its authoring pack:

```text
python scripts/install-examples.py vanilla-enhanced --pack-dir "<game>/codex/advancements"
python scripts/install-examples.py framework-showcase --pack-dir "<game>/codex/advancements"
```

Existing conflicting files or sidecar entries require `--replace`, which creates `.example.bak` backups. Sidecar maps merge with unrelated definitions. Start/reload Codex afterward and edit these through the same GUI.

- **vanilla-enhanced** expands 24 existing vanilla advancements, retaining native objectives, parents and requirement groups. Getting cobblestone gains a small claimable provisions reward. This is a progression sample, not a complete rework of every vanilla tree.
- **framework-showcase** creates the independent **Camp Chronicle** tab. It demonstrates GUI-authored choices, exclusive branches, delivery/statistic repeats, group/individual party rewards, treasure bags, conditional letters and milestone effects without NPCs, quest boards or fixed locations. The old **story-demo / Field Journal** remains a standalone legacy installer example; it is not added alongside Chronicle on fresh development launches.

## Parties and storage

Use `/codex party invite <player>`, `/codex party accept`, and `/codex party leave`. Ordinary vanilla advancements intentionally share. Partial criteria persist for offline members and reconcile on reconnect. Claims stay personal; submissions use the party's shared owner. Rejoining the same party does not re-import the same personal deliveries.

Authored definitions stay at `<game>/codex/advancements` and synchronize into the world's `datapacks/codex_advancements`. Runtime state lives under `<world>/codex`: claims, deliveries, parties and the party criterion ledger. Worlds remain independent.

On first use, an existing world imports legacy state only for identities already present in its `playerdata` folder, retaining legacy source files and `.legacy.bak` copies. New worlds start clean. Existing shared legacy history cannot be disentangled into its original worlds retroactively.

## Quest story, cycles, bags and tracker

Click a node in play mode to open quest details. This adds optional presentation around the same advancement: narrative, task progress, acceptance, submission, claiming, story choices and a tracker pin. In Edit Mode, **Quest Story & Rules** opens graphical Story, Choices, Rules, Branch and Effects pages. Apply changes to the advancement draft, then Save from the main editor. Unknown metadata is preserved.

Repeat cycles currently support delivery and statistic objectives. Enable Repeatable, choose a cooldown, and make rewards claimable. Choose personal or party scope and cooldown, daily UTC or periodic availability. Accepted members complete the shared tally; party rewards offer one group set or one set for each participant. Complete and claim a cycle before starting another when available. Statistic tasks count changes from the cycle's acceptance baseline. Original native advancement completion stays earned. Party sharing of ordinary advancements is unchanged. Offline accepted members keep individual shares; late joins wait for the next cycle.

Choose **Treasure Bag** in the reward dialog, select a named treasure/loot pool, enter quantity/display name, and optionally choose an item model for its appearance. One registered bag item carries this data as components. Right-click a held bag to consume one and generate server-side loot. Full inventory overflow is stored, not dropped; quest details offer collection when space is available.

Use the header's **UI** button for independent Codex scaling and tracker options. Comfortable gives a smaller effective scale inside Codex when Minecraft is on Large. Pin up to three quests and select a HUD corner; tracker visibility is optional.

The **Camp Chronicle** example demonstrates these features and seeds missing entries while preserving edits. It is also installable with `python scripts/install-examples.py framework-showcase --pack-dir <game>/codex/advancements`. See the [step-by-step review walkthrough](../development/REVIEW_WALKTHROUGH.md).

Development demo clients now use the stable offline name `CodexDeveloper`, so repeated launches have the same player UUID. Override it with `-Pcodex_dev_username=YourName`. Previous random `Player###` profiles remain stored separately; changing the name intentionally selects a different identity. Production player authentication is unchanged.

## Creator tools and GUI recipes

The editor header **Tools** opens Problems, local progression/choice Preview, subtree Duplicate, Bulk Rules and offline Migrate. Story choices can link a chapter and apply its exclusive gate together; Show Choice When provides AND/OR conditions through quest/choice pickers. Effects configures an optional heading and sound. Reward Preview explains quantities, weights and rolls without generating loot.

[Exact creator controls and offline migration workflow](../development/CREATOR_TOOLS.md), [GUI recipes for every showcase feature](../development/GUI_AUTHORING_RECIPES.md), and [actual-game review images](../development/CREATOR_REVIEW_2026-11-01.md). Complex unsupported native predicates and external migration references retain an advanced/manual path; these tools do not silently convert them.
