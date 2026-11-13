# Recreate the Camp Chronicle through the GUI

Every demo node is an ordinary advancement. Optional rules live in the quest sidecar; repeat cycles never revoke native advancement progress. Production jars do not install this demo. Normal development clients seed it into the editable authoring pack.

In Edit Mode, create a tab, choose its icon/title/background, then create child nodes. Select a criterion and use its Type selector. Item, recipe, biome and statistic selectors are searchable. Apply a dialog to the draft, then **Save** the advancement editor to write/reload the pack. **Cancel** discards a dialog draft; **Undo Apply** restores the previous advancement draft. Advanced JSON remains available for unsupported native predicates.

| Node | Criterion authoring | Story & Rules | Claimable rewards |
| --- | --- | --- | --- |
| The Camp Chronicle | **Always (tick)**, no parameters | Introduction/completion pages. Two Choices: campkeeper and wanderer; write outcome text. Link each Next Chapter using the picker. | None |
| A Small Head Start | **Always (tick)** | Optional explanatory text | Item rows: 16 oak logs, 32 cobblestone, fletching table, black bed; Treasure Bag named Camp Satchel, Field Provisions pool |
| Keeping the Camp Supplied | **Submit Items → Edit Items**:4 oak logs and8 cobblestone | Acceptance On; Repeatable On; After cooldown30 seconds; Personal; intro/progress/completion pages | XP5; Treasure Bag/Camp Satchel/Field Provisions; Random Treasure/Field Provisions |
| Stretch Your Legs | **Statistic Threshold → Edit Statistic**:custom statistic → Jump, target5 | Acceptance On; Repeatable On; After cooldown15 seconds; Personal |4 torches |
| A Little Kindling | **Submit Items**:2 sticks | Acceptance On; Repeatable On; Daily (00:00 UTC); Personal | XP2 and1 bread |
| For Everyone at Camp | **Submit Items**:8 cobblestone | Acceptance On; Repeatable On; After cooldown30 seconds; Party: shared progress & loot |4 bread and8 torches: ONE set for the group |
| A Place to Return To | **Item Obtained**:crafting table | Acceptance On; Branch → Chronicle root → campkeeper; intro/completion pages | XP10 and3 bread |
| Let the Trees Be Your Roof | **Reached Location (Biome)**:forest | Acceptance On; Branch → Chronicle root → wanderer; intro/completion pages | XP10 and3 apples |

The table uses display descriptions rather than registry syntax. Internal criterion/choice IDs are generated/retained by the editor. Names can differ from historical copies of the examples; the criterion, rules and rewards identify the recipe.

## Treasure list

Open **Treasure Lists**, add Field Provisions and set 2 rolls. Add item rows through the item picker: bread, torches and iron ingots. Each row exposes quantity minimum/maximum and weight. Save it once, then select it from Random Treasure or Treasure Bag rewards. The server generates the outcomes; a bag's display name and item model can be configured in its reward dialog.

## Dialogue controls

A choice has a label, outcome text, optional offering stage and optional next-chapter link. Stages are Any time, Before acceptance, While in progress, and After completion. A link offers navigation; the destination's Branch rule explicitly gates its native criterion awards and Codex acceptance/submission/claims. The alternate branch dims and explains why it is unavailable. Advancements without a Branch rule keep ordinary vanilla behavior. Choosing a chapter never prevents the player from crafting, exploring or otherwise playing Minecraft. Choices are saved once per quest/cycle. Ordinary and personal quests keep personal choices; a shared cycle keeps its choice with the party cycle. The result screen shows the saved outcome and links to matching unlocked chapters. Reopening it shows the saved choice rather than unexplained disabled buttons.

## Schedules and party rules

Daily means next00:00 UTC after completion. Fixed intervals align to server epoch time. Cooldowns count from completion. Start the next cycle explicitly after claiming: a schedule never silently discards incomplete deliveries or unclaimed rewards. Server time controls availability.

Party cycles snapshot membership at acceptance, including offline members. Those members share delivered quantities and statistic deltas. A member offline at acceptance starts a statistic baseline when first observed online. New members wait for the following cycle. Actual item consumption records each participant's delivered count. **For Everyone at Camp** keeps one group reward ledger: whoever claims an entry receives it, and no other member can claim it again. **A Share for Everyone** uses **Each participant**: every accepted member has a separate once-only claim, and the next cycle waits for the remaining members' claims. Offline members keep their shares; leaving forfeits an unclaimed share and does not copy the cycle into personal progress. Reward-policy edits take effect next cycle. Changing scope leaves old personal/shared files intact.

## Creator utilities

**Tools** in the editor opens Problems, Preview, Duplicate, Bulk Rules and Migrate. Problems link to affected definitions; Preview simulates fresh/accepted/completed/claimed and choice states locally; Duplicate preserves unknown JSON and remaps links inside copied subtrees. Bulk controls default to Keep Current. Migrate exports an offline plan for selected closed worlds. [Creator tools](CREATOR_TOOLS.md) explains exact controls and boundaries. Reusable style/reward presets and a broader dialogue graph remain future work.

Avoid adding visual effects that obscure icons or progress. Hover lift, green acceptance sweep, item-slot pulses and gold completion/claim effects remain local to their icons. Dots form one upper-right row: teal acceptance, purple story/decision, gold claimable rewards (muted before completion), blue repeatability. A finished one-time quest has no claim marker. Animation, receipts, sound and hold acceptance have client switches; ordinary clicks remain available.

Delivery receipts show the latest action while connected. Persistent delivery totals remain in the quest state; Codex does not retain an excessive action history.

## Creator tools follow-up

[Dialogue/branch authoring, diagnostics, local preview, duplication, bulk rules and tag/property deliveries](CREATOR_TOOLS.md) describes the current controls. Linked chapters can now receive their matching branch requirement in the same Apply operation.

**Timber with a Story:** create a child of A Place to Return To; Deliver Items → Add Tag → Logs, quantity4; Add Item → Oak Log, quantity1 → Props → Exact custom name `Camp Timber`. Rules: acceptance and repeatable,15-second cooldown; manual apple/XP rewards. Branch: Chronicle → campkeeper. No registry syntax or component JSON is required.

The vanilla example set now contains24 advancements: the entire16-node Minecraft Story tree, Adventure root, four Nether milestones, two End milestones and planting a seed. Existing native criteria, requirement groups and parents are retained. Modify each existing advancement's display/story pages to reproduce the enhancement. The six Sagittary/Sleep Cycle examples add only sidecar dialogue; the mod JAR advancement JSON remains untouched. See NATIVE_DEMO_SOURCES.json for exact native examples and source fingerprints.

**A Share for Everyone:** Deliver Items → four Sticks; require acceptance; Repeatable → Party → Each participant;15-second cooldown; manual reward one Apple. Both participants contribute to one tally and each claims once.

**Letters from the Road:** Always (tick); story choice controls → Show Choice When → one required group for the Chronicle branch choice, another for the relevant completed chapter. Outcomes are plain authored text; no NPC or coordinates.

**A Heading of Your Own:** Obtain Item → Compass; Effects → Chapter Celebration On, heading `A heading of your own`, sound picker → Toast Challenge Complete. Obtain a compass normally or use `/give @s minecraft:compass` in the disposable review world. This new node demonstrates the effect even on an existing development pack, whose prior edited chapter metadata is preserved.
