# Authoring and reviewing a quest pack

Open Advancements, enable Edit Mode, select a quest and choose **Tools** in the header or **Pack Creator Tools** in the sidebar. Most tools use the current drafts. Migration exports describe the last server-saved definition, so save edits before exporting.

## Dialogue and real branches without JSON

1. Select the story quest and open **Quest Story & Rules**.
2. In **Story**, write Introduction, In Progress and Completion pages.
3. In **Choices**, add a choice, give its button a label, choose when to offer it and write the outcome text.
4. Use **Link Next Chapter** to select an existing advancement by title. Turn **Chapter requires this choice** on for an exclusive branch. A newly linked chapter defaults to requiring the choice. Turn it off for a navigation-only link.
5. Save Choice, then Apply to Draft. The source story and linked chapter requirement become editor drafts together. A conflicting chapter requirement is rejected rather than overwritten.
6. Repeat for alternatives. Save the main editor to write all changed drafts to the server. Ordinary native criteria remain the basis of the chapters; the explicit branch rule also prevents native criterion awards in excluded branches.

The separate Branch tab remains useful when linking an existing authored story manually. A link button offers navigation; the destination's branch rule controls eligibility. Renaming a button label keeps its stable choice identity. Changing an identity in raw JSON is a data migration, not a cosmetic rename.

## Problems

The Problems page checks parent cycles/missing parents, invalid requirement groups and quest rules, missing story choices, circular story dependencies, missing linked chapters and available reward loot references. Navigation-only links are warnings, since they can be intentional. Click a result to return to the affected quest in the editor. Existing save-time validation remains in force.

The page checks the current definition snapshot. It cannot establish gameplay compatibility, validate arbitrary function effects or replace dedicated-server tests. If loot metadata has not arrived, loot-reference checks are omitted.

## Preview

Select a quest, choose Fresh Player, Accepted, Completed or Rewards Claimed, and simulate a dialogue choice. Switch to another chapter to inspect the resulting branch lock. Narrative pages, objective status and reward mode follow the simulated state. These are local presentation states, not an inventory/trigger simulator. Preview never accepts quests, submits items, claims rewards or modifies saved progress.

## Duplicate

Select an advancement and choose a new root identifier. Include Child Quests copies its subtree in one operation. Copied parents, choice links and branch requirements pointing within that subtree follow the copies; links to external quests stay external. Unknown definition fields remain intact. ID collisions abort before changing drafts. Copies begin with no copied player progress. Save the main editor when satisfied.

## Bulk rules

Each control defaults to **Keep current**. Apply acceptance and manual-claim settings across a selected quest and optional descendants. Other definition fields stay intact. Invalid repeat/reward combinations are rejected before applying the batch. Linked and bulk changes can be undone as a batch while their drafts still match; undo later edits on affected quests first. Copies can also be removed with batch Undo before saving. The final editor Save is still required.

## Deliver tags and item properties

Choose Deliver Items in a criterion dialog. **Add Item** opens the item picker; **Add Tag** opens searchable synchronized item tags. Each row has a quantity and **Props**. Properties support an exact custom name, potion type, damage value, and copying explicit component values from an item held in your main hand. An advanced component editor preserves complex cases.

A tag means any matching item can contribute to that requirement. A property requirement consumes only matching stacks. For overlapping requirements, the server computes an allocation across all eligible stacks before consumption; one physical item counts once. Partial progress is stored against the predicate's stable identity. Plain item-ID rows retain their original progress keys. Changing a tag or exact property changes the new predicate's identity; that requirement starts its own tally. Changing quantity does not change identity.

The **Timber with a Story** Chronicle example demonstrates an ordinary log tag and a named oak-log requirement together. Both can be authored through these controls.

## Conditional dialogue

Inside Edit Story Choice, use **Show Choice When**. Add a required group, select a quest by title and choose Completed, Accepted, All Rewards Claimed or Story Choice Saved. An optional Invert control requires the opposite. Add another required group for AND, or use **+** beside a condition to add an OR alternative in that group. Empty conditions keep the existing stage-only behavior. The server evaluates the rules; unavailable choices explain their conditions in a tooltip.

**Letters from the Road** demonstrates two condition-based replies. One requires the campkeeper decision and a completed timber contract; the other requires the wanderer decision and its chapter objective. Both live in the ordinary Chronicle advancement tab.

## Party reward policies

Rules → Repeatable → Party exposes **One group set** and **Each participant**. The default keeps the existing single group reward set. Each participant gives every member present at acceptance a separate once-per-cycle claim, while objectives remain one shared tally. The policy is fixed for the accepted cycle; edits apply on the next acceptance. The next cycle waits for remaining members' claims. Offline members retain membership and their unclaimed shares. Leaving the party forfeits a pending share. **A Share for Everyone** is the editable example.

## Milestone feedback and reward previews

Effects enables an optional chapter celebration, heading and searchable sound. Preview Celebration is local and does not complete the quest. Client notice/sound/animation preferences still apply. Campkeeper and wanderer chapters include editable examples.

Quest details → Reward Preview is read-only. Select an icon to inspect ordinary rewards or treasure quantities, per-roll weights and rolls. Unexpanded Minecraft/mod loot tables are labeled as such; previewing never rolls or reserves a reward.

## Native objective reuse and stable keys

The criterion dialog includes **Copy Existing Native Objective**. Choose a source advancement by title, then its objective. The copied definition retains complex native/mod predicates without requiring JSON; graphical controls remain available for supported shapes. Advanced JSON preserves unsupported details.

Existing criterion keys are kept stable in the ordinary dialog because saved native progress is keyed by them. Display text, descriptions and choice labels remain editable. Duplicating a quest creates new progress; use the offline migration tool for an intentional rename.

## Offline quest and criterion migrations

Save the editor, open **Tools → Migrate**, select a custom quest and choose Quest Identifier or Criterion Key. Export Plan writes a fingerprinted JSON plan and a copy of the maintained Python utility under the client's `codex` directory. The export contains authoritative stable reward identities so an ID rename does not create a fresh reward entitlement.

Close every affected world/server. On Windows-managed worlds, use Windows Python so world locks are verified by the same operating system as Minecraft. Preview first:

```text
python scripts/migrate-advancement.py --plan "PLAN.json" --pack "GAME/codex/advancements" --world "GAME/saves/WORLD_A" --world "GAME/saves/WORLD_B"
```

The exported script can replace the repository path above. Review the affected-file list, then repeat with `--apply`. Include every related world listed by the preview. Exporting selects or changes no world. Save edits and export again if a fingerprint has changed. Native vanilla/mod/datapack fallback IDs remain protected; duplicate those advancements instead.

The utility migrates native criterion timestamps/requirement references and known Codex story gates, conditions, layouts, deliveries, baselines, claims and cycle history. It synchronizes each selected world's generated datapack before startup. Inventories, XP, statistics, mailboxes and arbitrary functions are not rewritten. Detected advancement commands in functions require manual updates first; other external scripts/references still require the author's review.

Preimages are backed up under the authoring pack's `codex/migration_backups`. An interrupted operation leaves `codex/migration_pending.json`; run the same closed-world command with `--recover` to restore those preimages. Handled write failures roll back automatically. Applying the same plan again is idempotent; applying it to another explicitly selected world later uses the recorded original definition.
