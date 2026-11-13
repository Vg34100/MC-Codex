# Recipe editor

Operators can open **Recipe Editor** in the advancement screen's edit sidebar. In singleplayer, enable commands. Restart Minecraft after installing an updated Codex build; a resource reload cannot load new Java screens.

## Create a recipe

1. Select **New Shaped**, **New Shapeless**, or **New Cooking**.
2. Click an ingredient slot. Choose an item from the searchable picker, choose an item tag (for example, any logs), or clear the slot. These are ghost ingredients; creating recipes does not consume your inventory.
3. Select **Choose Output…**, choose an item, and set its quantity.
4. For cooking, select Furnace, Blast furnace, Smoker, or Campfire. Set experience and cooking time in ticks (20 ticks = one second).
5. Give the recipe a unique identifier. Selecting an output supplies a default under `codex:custom/`; change it when making another recipe for the same output.
6. Select **Save Recipe** and wait for **Recipe saved and reloaded**. The server validates the recipe before writing it, then reloads the datapack. No restart is needed for a successful recipe save.

Shaped recipes use the grid arrangement, including vanilla's usual shifting/mirroring rules. Shapeless recipes use each occupied slot once and ignore placement. Multiple copies of an ingredient require multiple slots, not a stack quantity. Tags allow any item belonging to that tag. Saving invalid or empty ingredients produces an error rather than a usable recipe.

**Cancel / Back** asks before discarding changed documents. An existing recipe's identifier is fixed so an edit cannot accidentally become a second recipe.

## Edit and restore

Search the catalog by recipe identifier or its words, such as `ender eye`. It includes recipes from vanilla, installed mods, and active datapacks. Select a result to edit it. Recipes authored through Codex show **[edited]**.

Saving an existing recipe creates an override with the same identifier. It does not change Minecraft's or a mod's JAR. **Restore Original Recipe** removes only Codex's override after a second confirmation. **Remove Custom Recipe** removes a recipe that has no underlying original. A `.bak` backup is retained. The original definition returns if one exists in another active pack. Later saves replace the previous backup; this is not an unlimited undo history.

The server rejects saves when another operator has changed the document since it was opened. Reopen the recipe and review that version. Reload failures attempt to put the previous file back and reload it; inspect the server log if recovery also fails.

## Supported data and boundaries

Visual editing supports native shaped/shapeless crafting, smelting, blasting, smoking, and campfire cooking. Item outputs, quantities, item/tag inputs, cooking experience, and time are editable. Existing unedited fields are retained, including output components and mod-specific fields. Unsupported ingredient expressions remain intact until explicitly replaced.

**Advanced JSON…** is available for other recipe serializers and fields without visual controls. The installed server must know the serializer and referenced items/tags. Codex uses Minecraft's native recipe codec to validate the document. A visual editor for every mod's custom machine is not included.

Codex does not automatically create a recipe-book unlock advancement. A saved recipe can be crafted normally; recipe-book discovery can be added through an advancement recipe reward or `/recipe give`. A recipe-book unlock is not an enforced crafting progression gate. This editor also does not remove arbitrary original recipes without replacing them.

## Storage and sharing

Native JSON is written under the existing editable pack, `<game-or-server-directory>/codex/advancements/data/<namespace>/recipe/<path>.json`, then synchronized into the active world's Codex datapack. The authoring pack belongs to that game/server installation and is shared by worlds using it. It is not a per-world recipe workspace. Copy the complete pack, including `pack.mcmeta`, into your pack distribution when sharing it; clients do not need to edit recipes locally on a dedicated server.

## Validation

The focused dedicated-server harness is `python scripts/test-recipes.py all` on Windows. It uses `build-smart.py`, isolated worlds under `runs/recipe-validation`, and reports native recipe matching, outputs, malformed-data checks and payload bounds. It does not represent two real player clients.

A separate Fabric 26.1.2 Mine MCP walkthrough exercised the actual GUI: item/tag selection, creation/save/reopen, overriding the vanilla Eye of Ender output, and restoring its original definition. No desktop mouse or keyboard input was used. NeoForge received server tests and build checks; its GUI has not been separately playtested for this feature.
