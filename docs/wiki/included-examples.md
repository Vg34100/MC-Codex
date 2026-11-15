# What comes with Codex

The downloaded mod includes six enhanced vanilla advancements and one separate, twelve-quest **Camp Chronicle** tab. These are small editable examples, not a complete modpack. No content mods, changed recipes, fixed map, NPCs, or mandatory quest board are included.

## Vanilla field notes

These retain the original identifiers, parents, objectives, requirement groups and native rewards. Existing completion remains attached to the same advancements. Codex changes their presentation and attaches optional dialogue:

| Vanilla advancement | Included title | Addition |
| --- | --- | --- |
| Minecraft root | Minecraft: Field Notes | Introduction |
| Stone Age | Stone Tools | Journal entry and optional claim of 3 bread/5 XP |
| Acquire Hardware | First Iron | Journal entry |
| Getting an Upgrade | A Better Pick | Journal entry |
| We Need to Go Deeper | Into the Nether | Journal entry |
| A Seedy Place | Grow Some Food | Journal entry |

The rest of the vanilla trees keep their normal presentation. Vanilla advancement progress remains vanilla progress; the optional bread/XP claim is separate Codex state.

## The Camp Chronicle

This independent tab opens automatically in a fresh world. Its twelve quests demonstrate:

| Quest | What to try |
| --- | --- |
| The Camp Chronicle | Page through Field Notes in the RPG dialogue panel; choose campkeeper or wanderer, read the confirmed outcome and open the linked chapter |
| A Small Head Start | Optional one-time starter gift: 4 logs, 8 cobblestone and a treasure satchel |
| Set Up Camp | Campkeeper branch: obtain a crafting table |
| Keep Exploring | Wanderer branch: find a forest |
| Camp Supplies | Accept, deliver 4 oak logs/8 cobblestone, claim provisions, repeat |
| Stretch Your Legs | Accept, jump five times, claim, repeat after cooldown |
| A Little Kindling | Personal daily stick delivery, resetting at 00:00 UTC |
| For Everyone at Camp | Shared cobblestone delivery; one group reward set |
| A Share for Everyone | Shared stick delivery; a reward for each participant |
| Marked Timber | Deliver any logs plus an oak log named Camp Timber |
| Checking In | Dialogue replies that depend on your story progress |
| A Way Back | Obtain a compass; see an optional completion celebration |

The opening journal demonstrates a book icon, speaker label, skippable text reveal, multiple pages and saved branch outcomes. The camp/trail chapters and supply contract use their own item icons. To recreate these settings, use Edit Mode → Quest Story & Rules → Story → Speaker & Presentation. Untouched installer-owned examples update automatically; author-edited entries are preserved.

Solo players can try party contracts alone. The tab includes a compact authored layout. All examples are editable with Codex's existing GUI; advanced JSON remains available. Dots/hover text explain acceptance, dialogue, delivery and repeatability. A branch choice shapes the sample story without limiting ordinary Minecraft exploration.

## Configure or remove the examples

`config/codex-examples.json` is created on the client installation hosting singleplayer, or on the dedicated server:

```json
{
  "vanilla": true,
  "showcase": true,
  "sagittary": true,
  "sleepcycle": true
}
```

Set `vanilla` or `showcase` to `false`, then restart the game/server. On a fresh installation these switches prevent installation. For already installed examples, disabling removes only installer-owned, unchanged content and keeps backups. Sidecar entries belonging to other authored quests are retained. Re-enabling restores content the switch removed; restarting does not recreate quests you deleted yourself in the editor.

**If you customized an example group, that group and its dependencies are retained when disabled.** This protects your work from deletion and avoids orphaned branches. Export/back up your changes, then remove unwanted nodes through the editor; setting a switch is not permission to delete customized content. Settings and authoring examples belong to the game/server installation and apply across worlds that use its authoring pack. Player progress remains world-specific.

Existing author definitions take precedence. New versions seed missing example entries rather than overwriting existing edits. Example ownership is tracked in `codex/bundled_examples.json`; keep that file when moving the authoring installation. Do not delete it as a way to disable content.

## Optional companion mods

Sagittary and Sleep Cycle are not bundled or required. When installed separately, their native advancement trees remain theirs. Codex supplies optional journal entries for three milestones in each mod, controlled by the corresponding switches above. Without those mods, their example sidecars are not installed and no imitation mod tree appears.

The development client uses the same bundled selection as a release installation; development companion JARs remain optional local runtime dependencies. Older development worlds can still contain your previous larger examples, because Codex preserves existing authored content. Use a fresh isolated installation to review exactly what a new download includes.

The separate 60–100 quest vanilla-plus pack will have its own distribution, recipe changes and progression. None of that larger campaign is installed by these examples.
