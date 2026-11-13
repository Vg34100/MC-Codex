# Codex Survival — playtest0.1

Minecraft **26.1.2**, **Fabric Loader0.19.5**, **Java25**. This is a separate pack, not content added to every Codex installation.

## Play

Select **Codex Survival - Playtest** in Prism and create a **new Survival world**. Existing worlds are not included or modified. If Prism was already open when the instance was added, refresh its instance list or restart the launcher.

Open Advancements (normally **L**) and read **Start Here**. Eight pack tabs contain80 quests; Minecraft and installed mods retain their native advancement tabs. Deep branches reveal through normal advancement visibility. Edit Mode with commands/operator permissions exposes every definition for authoring.

The main objective is to collect **twelve different End Remastered eyes**, open the stronghold portal, defeat the dragon, then craft the **Survival Star**. Four eye routes may be skipped. The final trophy combines materials/equipment gathered throughout the pack and dragon's breath from the fight. It has no custom gameplay power. You do not need to complete every optional quest to finish.

JEI shows recipes. Press the recipe-view key shown in its controls (normally R over an item) and inspect the recipe book. Pack recipes unlock in the recipe book automatically; this is discovery, not enforced quest gating. Other routes to native items remain available.

## Included content

- Codex, The Spelunker Update, Fishing Frenzy, Combo and Sagittary from local26.1.2 Fabric distribution builds.
- End Remastered, Farmer's Delight Refabricated, Dungeons and Taverns, Backpacked.
- JEI, Jade, AppleSkin, Mod Menu, Sodium, Lithium, FerriteCore and required libraries.
- Exact builds/sources/hashes in `mods.lock.json`. No versions from other Minecraft releases are selected.

Combo0.1.0 is the existing compatible local26.1.2 build. Its melee, parry, stamina and dodge controls are available in Controls/Mod Menu. Combat quests honestly track normal kills/equipment; they do not detect a finisher or parry. The Spelunker Update foundry uses its native mechanics and JEI integration. Fishing Frenzy uses its native bait/rod mechanics and tooltips.

## Pack changes

Seven shaped crafting recipes are authored through ordinary native recipe JSON:

1. Additional **Cryptic Eye** route: electrum, lapis block, diamonds, book, Ender Pearl.
2. Additional **Undead Eye** route: invar, wither skeleton skull, bone block, Ender Pearl.
3. Additional **Cold Eye** route: packed ice, tin, Ender Pearl.
4. Revised **Exotic Eye**: carp, bream, nautilus shells, electrum, Ender Pearl.
5. **Backpack**: leather, Farmer's Delight canvas, bronze.
6. **Deluxe fishing rod**: bronze and string.
7. **Survival Star**: bronze/invar/electrum blocks, deluxe rod, compound bow, nether star, heart of the sea, dragon's breath, diamond block.

Inspect actual quantities and arrangements in JEI or Codex's Recipe Editor. Native eye sources still work. Core mining, farming, combat, equipment and structure mechanics otherwise remain owned by their mods. No compulsory map, NPCs, scripted locations or invented story campaign is required.

Three optional delivery contracts accept surplus building supplies, carp or beef stew. Accept before submitting; partial deliveries consume only credited items. Claim once per cycle; another cycle opens after five minutes. Contracts are not required for End access. Claim dots and hover text explain rewards; pin a goal for a compact tracker.

The small bundled Codex examples are disabled here to avoid a second Camp Chronicle/demo. Pack authoring content is under `codex/advancements`, shared by worlds within THIS Prism instance; player progression remains world-specific. Use a separate instance when changing progression for a different campaign.

## First playtest priorities

- Can you find tin and make the foundry/bronze without a confusing or circular requirement?
- Does the backpack/rod progression feel useful and reasonably priced?
- Do fishing and cooking provide useful preparation without becoming chores?
- Are the alternative eye recipes fair relative to exploration sources?
- Are quest text, claim dots, partial deliveries and tab navigation understandable?
- Does the End approach feel like completing several substantial expeditions?

This is a first playable build. A complete survival playthrough and balance pass have not been done. The80 quests include chapter introductions and optional goals, not80 mandatory gates.

## Known upstream log noise

The selected Backpacked version can log missing addon metadata when reading ordinary datapacks; the pack still loads. The local Spelunker Update build includes an ignored duplicate texture filename and its custom foundry recipes produce vanilla recipe-placement warnings. Actual foundry production across a full survival progression still needs playtesting. Offline authentication warnings belong to the isolated automated test launcher, not this Prism instance's account settings.

## Authoring and rebuilding

Source: `modpacks/codex-survival` in MC-Codex. `tools/pack/build-survival-content.py` generates native advancements, sidecars and recipes. Generated files remain fully editable in Codex. Do not regenerate over an instance you have edited: export/back up its authoring folder first.

On Windows, `tools/pack/stage-survival-pack.py` checks locked JAR hashes and stages content. `tools/pack/install-prism-survival.py` creates only a NEW `CodexSurvival-26.1.2-Fabric` instance and refuses to overwrite one that already exists. The local resolver can select refreshed builds; the installed playtest remains pinned by its lock. No test-control mod or test-player progress is installed.

Nothing has been published. A public Modrinth release would need a full playthrough/balance review and a distribution/export pass for all dependency download sources.

Quest tabs use darker gray frames. Their icons gently pulse for specific pending actions, explained in the tab tooltip. The entire frame turns advancement gold after all objectives, rewards and required decisions are finished. Hover an item in a quest card and press JEI's recipe/uses keys (normally R/U) to inspect it. Claim and Claim All show custom reward receipts. These UI changes require restarting Minecraft after updating Codex; quest-text changes require leaving and rejoining the world.
