# AGENTS.md

This file is a reusable workflow guide for agentic work in a typical Architectury/Fabric/NeoForge Minecraft mod repo.

Use it as the first file to load before exploring the tree.

Before changing code in a fresh copy of this template, run `python rename-template.py ...` first so the placeholder mod id, package, and starter class names are replaced immediately.

## Goals

- Minimize context waste.
- Prefer fast search over broad file reading.
- Keep user work safe in dirty trees.
- Make changes loader-aware.
- End with a clean, inspectable commit history.

## Context Discipline

- Search first. Read second. Edit last.
- Prefer `rg --files` and `rg -n` over opening directories or large files blindly.
- Open only the exact files on the active path.
- Reuse known build/test commands instead of re-deriving them each turn.
- Summarize findings instead of repeating raw command output back to the user.

### Build Output Context Waste (CRITICAL)

**The #1 source of context waste is verbose build/compiler output.**

Common mistakes that waste context:
- Running `./gradlew build 2>&1 | tail -200` (grabs too much)
- Not filtering compiler error output (same error repeated 3x)
- Full stack traces for simple "symbol not found" errors
- Gradle boilerplate warnings about deprecated features

**Solutions:**
1. Use `build-smart.py` (see Compile/Test Workflow section)
2. If raw gradle is needed, filter aggressively: `| grep -E "(error:|BUILD)" | head -20`
3. Fix multiple related errors before rebuilding (don't fix-rebuild-fix-rebuild)
4. Read error messages carefully - often one fix resolves many errors

## Fast Search Workflow

### Find files

```bash
rg --files
```

### Find text or symbols

```bash
rg -n "pattern" common/src/main/java fabric/src/main/java neoforge/src/main/java
```

### Find assets or data

```bash
rg --files common/src/main/resources/assets common/src/main/resources/data
```

## Typical Minecraft Mod Layout

These are the first places to check in most multiplatform mod repos.

### Common code

- `common/src/main/java/...`
- shared registries
- shared blocks, items, menus, block entities, worldgen, recipes, screens

### Fabric code

- `fabric/src/main/java/...`
- Fabric-only bootstrap
- Fabric-only client hooks
- Fabric-only events and data hooks

### NeoForge code

- `neoforge/src/main/java/...`
- NeoForge-only bootstrap
- NeoForge-only client hooks
- NeoForge event bus registration

### Resources

- `common/src/main/resources/assets/<modid>/textures/block/`
- `common/src/main/resources/assets/<modid>/textures/item/`
- `common/src/main/resources/assets/<modid>/textures/gui/`
- `common/src/main/resources/assets/<modid>/models/block/`
- `common/src/main/resources/assets/<modid>/models/item/`
- `common/src/main/resources/assets/<modid>/blockstates/`
- `common/src/main/resources/data/<modid>/recipe/`
- `common/src/main/resources/data/<modid>/loot_table/`
- `common/src/main/resources/data/<modid>/worldgen/`
- `common/src/main/resources/data/<modid>/tags/`

## How To Inspect Vanilla Minecraft Classes

When you want to copy or adapt vanilla behavior, do not guess. Inspect the mapped Minecraft sources available through the Gradle/Loom caches.

### Fastest practical rule

- Search your own code first.
- If the behavior is clearly based on a vanilla menu, block, screen, feature, recipe book, or renderer, inspect the matching vanilla class before editing.

### Common places to look

For Architectury/Loom projects, mapped Minecraft jars usually live under `~/.gradle/caches/fabric-loom/`.

Useful examples:

- merged named jar for browsing classes:
  - `~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/...`
- loader/library source jars:
  - `~/.gradle/caches/modules-2/files-2.1/...`

### Quick class search

Use `jar tf` plus `rg` to find likely vanilla classes:

```bash
jar tf ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/*/*.jar | rg 'MenuScreens|RecipeBook|FurnaceScreen|CreativeModeTabs'
```

### Read a mapped source file from a sources jar

If a sources jar exists, prefer that over decompiling bytecode:

```bash
python3 - <<'PY'
import zipfile, glob
path = glob.glob('/home/$USER/.gradle/caches/modules-2/files-2.1/**/**/**/*sources.jar', recursive=True)[0]
with zipfile.ZipFile(path) as z:
    for name in z.namelist():
        if name.endswith('SomeVanillaClass.java'):
            print(z.read(name).decode('utf-8'))
            break
PY
```

In practice, narrow the glob to the exact dependency first.

### Bytecode inspection when no source exists

For MC 26.1.2 with `loom-no-remap`, source jars may be absent or incomplete. When you need to understand an undocumented API (especially new rendering internals), read the bytecode directly:

```python
python3 - <<'PY'
import subprocess, zipfile

jar = "/mnt/c/Users/video/.gradle/caches/fabric-loom/26.1.2/minecraft-merged.jar"

# List members of a class
result = subprocess.run(
    ["javap", "-p", "-classpath", jar, "net.minecraft.client.renderer.SubmitNodeCollector"],
    capture_output=True, text=True
)
print(result.stdout)

# Show bytecode for one class (shows all method bodies + constant pool references)
result2 = subprocess.run(
    ["javap", "-c", "-p", "-classpath", jar,
     "net.minecraft.client.renderer.block.BlockModelRenderState"],
    capture_output=True, text=True
)
print(result2.stdout[:3000])
PY
```

The constant pool `//` comments in `-c` output reveal what methods and fields each method actually calls — invaluable for tracing call chains through new rendering APIs with no docs. Use `-verbose` for the full constant pool up front if you need to trace across multiple classes.

This technique is what revealed that `BlockModelRenderState.submitWithZOffset()` calls `SubmitNodeCollector.submitBlockModel()` (correct path), while the actual issue was that the item/entity render pass doesn't apply world lighting — requiring `submitMovingBlock` instead.

### Good lookup targets by task

- menu/screen issue:
  - `MenuScreens`
  - matching vanilla screen class like `FurnaceScreen`, `AbstractFurnaceScreen`, `CraftingScreen`
- recipe book issue:
  - `RecipeBookComponent`
  - `RecipeBookMenu`
  - matching vanilla screen/menu implementation
- worldgen issue:
  - matching feature class and configured/placed feature patterns
- block behavior issue:
  - matching vanilla block class, especially survival/update/placement methods
- client rendering/model predicate issue:
  - matching vanilla item/block render registration path

### Efficiency rule

- Do not open random large Minecraft sources jars blindly.
- Search for the exact class name first.
- Open only the one or two vanilla classes closest to the feature being implemented.

## Loader Split Rule

If something works on one loader but not the other:

1. Check the shared implementation.
2. Check loader-specific bootstrap and client registration.
3. Do not assume Architectury abstraction is enough for every case.
4. If a shared helper is unstable on one loader, move that behavior into loader-native code.

Good examples:

- screen registration
- creative tab insertion
- render layer registration
- client predicates
- event wiring

## Common Debug Paths

### Screen/menu issue

Check:

- menu type registration
- menu open call
- client screen registration
- loader-specific screen event hooks

### Texture/model issue

Check:

- block/item model JSON
- blockstate JSON
- referenced texture path
- render layer if cutout/translucent behavior matters

Use `F3 + T` for texture/model reloads.

### Worldgen issue

Check:

- configured feature JSON
- placed feature JSON
- biome JSON generation-step wiring
- custom feature registration and custom feature code

### Recipe/book/UI issue

Check:

- menu class
- screen class
- recipe type registration
- recipe serializer/type wiring
- client-side category/filter hooks

## Dirty Worktree Rule

- Assume the tree is dirty unless proven otherwise.
- Never revert unrelated user changes.
- Treat modified PNGs, docs, and generated references as user-owned unless explicitly told otherwise.
- Stage only the files for the current fix.

Before committing, always inspect:

```bash
git status --short
```

## Docs And Wiki Workflow

- Treat repo docs as the source of truth.
- Write and update documentation in-repo first, not directly in the GitHub wiki UI.
- Use `docs/wiki/` for structured gameplay/system pages.
- Use a repo page like `docs/wiki/modrinth-front-page.md` for storefront/front-page copy drafts.
- The GitHub wiki does not auto-sync by default, so think of it as a publish target.

Preferred workflow:

1. Update or add the page in `docs/wiki/`.
2. Keep recipe, mechanic, and progression details aligned with the actual code/data.
3. If the user wants GitHub wiki updated, sync from the repo docs rather than rewriting from scratch in the browser.

If automation is later added, it should push repo docs into the GitHub wiki repo. Until then, avoid treating the GitHub wiki as the primary source.

## Compile/Test Workflow

### CRITICAL: Use the Smart Build Script

**ALWAYS use `build-smart.py` instead of raw Gradle commands.**

Raw Gradle output is extremely verbose (100-200+ lines per failed build) and wastes massive amounts of context. The smart build script parses errors and shows only essential information.

```bash
# On Windows (cmd.exe) - PREFERRED for this repo
cmd.exe /c "python build-smart.py"

# Available commands:
python build-smart.py              # compile only (default, fast)
python build-smart.py compile      # same as above
python build-smart.py compile:fabric    # compile common + fabric only
python build-smart.py compile:neoforge  # compile common + neoforge only
python build-smart.py build        # full build with jars
python build-smart.py shadowJar    # distribution jars
python build-smart.py release      # alias for shadowJar
python build-smart.py clean        # clean build dirs
```

**Default is `compile`** - fast compileJava only, no jar packaging. Use this during development.

**Example output comparison:**

Raw Gradle (BAD - 150+ lines):
```
A:\Projects\...\BoneShaftEffect.java:26: error: cannot find symbol
        return entity instanceof Zombie || entity instanceof Skeleton ||
                                 ^
  symbol:   class Zombie
  location: class BoneShaftEffect
... (100 more lines of repeated errors and gradle boilerplate)
```

Smart Build (GOOD - ~10 lines):
```
Running: gradlew.bat :common:compileJava :fabric:compileJava :neoforge:compileJava --no-daemon
------------------------------------------------------------
============================================================
BUILD FAILED
============================================================

Errors found:
------------------------------------------------------------
BoneShaftEffect.java:26: error: cannot find symbol
    symbol:   class Zombie
------------------------------------------------------------
Fix errors and rebuild
```

### Windows vs WSL

For this repo specifically, **use cmd.exe** because the gradle.properties has Windows-style Java paths.

```bash
# Preferred for this repo
cmd.exe /c "cd /d A:\Projects\The Experiment Lab\Minecraft\sagittary && python build-smart.py"
```

### WSL Mirror Build (for pure WSL projects)

For WSL-on-Windows or mixed-filesystem setups, prefer a mirror build to avoid path, lock, and Gradle cache issues.

### Reusable mirror compile loop

```bash
mirror=/tmp/mod-wsl
rm -rf "$mirror"
mkdir -p "$mirror"
rsync -a --delete \
  --exclude '.git' \
  --exclude '.gradle' \
  --exclude 'build' \
  --exclude 'fabric/run' \
  --exclude 'neoforge/run' \
  ./ "$mirror"/
cd "$mirror"
env GRADLE_USER_HOME=/tmp/mod-gradle-home \
    MOD_BUILD_ROOT=/tmp/mod-build \
    ./gradlew --project-cache-dir /tmp/mod-project-cache \
    --rerun-tasks \
    :common:processResources \
    :common:compileJava \
    :fabric:compileJava \
    :neoforge:compileJava
```

If a specific repo already has a known-good variant, prefer that exact command.

## Editing Rules

- Use the smallest patch that fixes the actual issue.
- Prefer loader-native fixes over forcing a shared abstraction when runtime behavior diverges.
- Avoid drive-by refactors unless they directly reduce future breakage on the active path.
- If you discover a reusable list or workflow, centralize it once instead of duplicating it in multiple loader files.

## Commit Workflow

Use the commit style the user has preferred in this repo:

1. Make one coherent change.
2. Run the relevant compile/test loop.
3. Stage only the files for that change.
4. Commit with a short conventional-style message.

Preferred commit shape:

- `fix: ...`
- `feat: ...`
- `refactor: ...`
- `style: ...`
- `docs: ...`
- `chore: ...`

Good examples:

- `fix: register foundry screen on neoforge`
- `fix: split vanilla creative tabs by loader`
- `docs: refresh wiki for current gameplay`

Avoid:

- giant mixed-purpose commits
- vague messages like `updates` or `misc fixes`
- committing user texture work unless explicitly requested

## Final Response Pattern

Keep closeout concise:

- say what changed
- point to the important file or two
- say what was verified
- call out anything not tested

Do not dump long terminal logs into the response.

