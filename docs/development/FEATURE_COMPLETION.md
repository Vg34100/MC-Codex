# Quest framework status — November 1, 2026

Codex extends ordinary vanilla, mod and datapack advancements. There is no required conversion to another quest format. This milestone implements the five requested reliability, authoring, demonstration, quest-depth and presentation priorities. It does not establish full FTB Quests feature parity or a finished vanilla content overhaul.

## What is available

| Area | Current implementation |
| --- | --- |
| Native advancement editing | Preserved source JSON, unknown fields, rich components, parents, frames, display flags, rewards and complex AND/OR requirement groups. Supported fields have visual controls; advanced predicates retain a JSON escape hatch. Copy Existing Native Objective reuses a criterion from another tree. |
| Editor workflow | Dedicated criterion/reward/item/treasure/story dialogs own Apply/Save/Remove/Cancel; searchable selectors; independent Comfortable GUI scale. Pack Creator Tools provides clickable diagnostics, local progression/choice preview, subtree duplication, bulk acceptance/claim rules and batch Undo. |
| Delivery | Visual item/tag rows with quantities and optional exact names, potions, damage or held-item components. The server allocates overlapping predicates before consumption; the same physical items cannot satisfy two rows. Partial progress persists. |
| Rewards | Item/XP/recipe/function/loot rewards; named weighted treasure pools with ranges and rolls; data-driven treasure bags; reward previews; manual and automatic rewards. Stable claim identities, overflow storage and receipt-based recovery prevent ordinary replays. |
| Progression | Persistent parties, intentional ordinary vanilla advancement sharing, quest acceptance, personal/shared delivery and statistic cycles, cooldown/daily/periodic resets. Shared rewards offer the existing group set or a separate once-per-cycle share for each participant. |
| Story | Optional introduction/progress/completion pages, saved choices/outcomes, linked chapters and explicit exclusive-branch gates. Choice conditions support accepted/completed/claimed quests and earlier choices with AND/OR groups and inversion. These supported forms are authorable through the GUI. |
| Presentation | Adjacent status dots in one upper-right row; sprite-shaped lock shading; local hover/hold/completion effects; exact delivery receipts; optional three-quest HUD; optional chapter heading/sound; detailed treasure previews. Client animation, notices and sound switches remain available. |
| Offline migrations | GUI exports fingerprinted plans and the maintained migration tool. Explicitly selected closed worlds can migrate custom quest IDs/criterion keys, native completion and known Codex references/claims with backups, rollback and stale-plan rejection. Native fallback keys remain protected. See [creator tools](CREATOR_TOOLS.md#offline-quest-and-criterion-migrations). |

## Three demonstrations

| Goal | Editable development examples | Limits |
| --- | --- | --- |
| Richer vanilla progression | 24 advancements: the complete 16-node Story tree, Adventure root, four Nether milestones, two End milestones and planting a seed. Native criteria, parents and requirement groups were compared with Minecraft resources and retained. | A representative authored progression, not an overhaul of every vanilla advancement. |
| Native mods | Unmodified Sagittary 2.0.1 and Sleep Cycle 1.0.5 load in both loader profiles. Six dialogue-only sidecars supplement their native trees without replacing advancement JSON. | Representative item objectives/editing are covered; every mod-specific predicate is not. [Native demo provenance](NATIVE_DEMO_SOURCES.json). |
| Independent narrative | Camp Chronicle retains its story/acceptance/delivery/statistic/treasure/bag examples and adds tag/property deliveries, individual party shares, conditional letters and a configurable milestone. | One saved choice per quest/cycle; no freeform dialogue graph canvas or arbitrary world-effect choice scripting. |

Normal Gradle/IntelliJ development clients seed missing example content and load the local companion JARs. Existing edited examples are preserved, so an older edited page can differ from the current recipe. Production JARs exclude examples, partner mods and test fixtures. See [GUI recipes](GUI_AUTHORING_RECIPES.md) and the [review gallery](CREATOR_REVIEW_2026-11-01.md).

## Validation boundaries

The review records separate evidence for actual rendered TCP clients, simulated dedicated-server scenarios, offline migration tests and packaging. Passing one category is not a claim about another. The 501-node client run is a capped rendering smoke check, not an uncapped performance benchmark. Local loopback clients do not establish behavior under remote latency or packet loss.

Dedicated tests cover native JSON preservation, datapack synchronization, party sharing, partial consumption, overlap allocation, duplicate claims, random rewards/bags, restart/world isolation, reset behavior and invalid requests. Actual-client profiles additionally cover transport, repeated navigation, story choice locks, reconnects and GUI authoring. Test code stays outside production artifacts.

## Remaining work

1. Broader manual usability review at more resolutions/scales, larger real packs/parties and delayed remote connections. Profile rendering without the test clients' low FPS cap before making a performance claim.
2. More visual controls for complex native/mod predicates and reusable style/reward presets. Raw editing and native-objective copying preserve complex definitions in the meantime.
3. Repeating arbitrary native triggers after original completion needs a separate safe design. Delivery/statistic cycles already repeat without revoking vanilla advancement progress.
4. A richer dialogue graph, more conditional outcomes and meaningful contribution displays. Current GUI choices, conditions and exclusive chapters work, but are not a general narrative scripting engine.
5. Operator-facing recovery for ambiguous interrupted functions. Codex reserves arbitrary functions rather than blindly replaying uncertain world effects. Current running/needs_review records require operator inspection. Fault injection is not a hardware power-loss guarantee.
6. More authored/reviewed vanilla and native-mod content. Development updates preserve edits instead of silently overwriting them.

## Additional versions

26.1.2 Fabric and NeoForge remain the supported targets. No other target was added or published. The requested completed Stonecutter template was unavailable locally and at the supplied repository location during this session; no replacement infrastructure was invented. Resume with that template, then prioritize 26.3 Fabric/NeoForge and distinguish packaging from gameplay results.
