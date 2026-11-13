# October 22 — local advancement feedback

The final marker layout is a **single horizontal row of adjacent dots anchored to the upper-right edge**. Earlier screenshots from this session show an intermediate square cluster or markers in separate corners; those are superseded. The last row screenshot still has a one-pixel gap; the final source removes that gap. That last spacing adjustment was compiled/packaged without another desktop takeover.

Implemented behavior:

- One renderer draws the visible frame and item. The tree copy is omitted under the hover card, so the actual visible artwork lifts/grows.
- Hold-to-accept sweeps green across the original artwork, then returns to its ordinary state after server confirmation. A cancelled hold retreats; a short click still opens details.
- Item slots pulse locally after confirmed delivery/obtain progress. Completion/claims briefly flash the frame gold with a few contained square highlights. Nothing flies between inventory and quest nodes.
- Teal dots mean acceptance; purple means story/decisions; muted/bright gold means future/ready manual claims; blue means repeatability. Claimed one-time quests settle to plain yellow without a claim marker. Repeatable quests retain their repeat marker.
- Relevant instructions are in the hover description. There is no universal click/hold footer or duration label. Upward-opening descriptions have 8px top padding; wrapping is wider.
- The open card owns pointer input within its real rectangle, including over covered nodes. Item submission, reward inspection and claiming remain reachable. Move outside the card to select a node it covers.
- Translucent receipts show exact server-confirmed negative consumed amounts and delivery progress, or claim/accept/error status. Text wraps at words. Animation, receipts, quiet sounds and hold acceptance have independent client switches. Vanilla advancement/recipe notifications still follow their authored vanilla settings.
- Acknowledged completion updates frames immediately while the singleplayer server is paused. Repeat frames use the active cycle. Explicit choice-gated branches dim and explain their exclusion; their native criterion awards and Codex actions are blocked.

## Review examples

Use the regular IntelliJ/Gradle development client. The existing Camp Chronicle is the playable example; production jars still exclude demo packs and companion mods.

1. **A Little Kindling**: accept, submit one stick and inspect 1/2, submit the second and watch immediate yellow completion. Claim XP and bread; the gold dot disappears, repeat/story dots remain. Daily wait uses hours/minutes.
2. **Keeping Camp Supplied**: hold to accept and click the exact log icon. Only that requirement is consumed; cobblestone remains outstanding. Motion stays inside its slot/frame.
3. **A Small Head Start**: claim all rewards with the node shortcut or individual reward chips. The final one-time frame has no dot. The kit also unlocks unchanged Sagittary and Sleep Cycle progress when its items are obtained/used.
4. **Chronicle root**: choose campkeeper. The crafting-table route opens; the sapling route dims and explains the exclusion. Obtaining a crafting table cannot complete the excluded sapling branch. Ordinary unrelated Minecraft play stays available.
5. **UI settings**: compare Comfortable with Minecraft scale. The two-column switches fit both reviewed scales at 1280×800 window size. Toggle animation, receipt or sound independently.
6. **Reset**: `/advancement revoke @s everything` clears acceptance/cycles, story, submissions and both claim ledgers as well as native criteria. Shared state and the stored native sharing ledger reset too; use all party members as targets for a full party reset. Retained teammates can intentionally re-share their own native progress. Inventory/XP/mailbox/statistics/party membership remain. Tick/inventory/location criteria can earn again naturally. Affected JSON is backed up under the world's `codex/reset_backups/`; reset postimages use the existing journal.

## Evidence and limits

Real Fabric and NeoForge reviews used only the owned review worlds. Preferences were restored byte-for-byte after closing each client. The latest NeoForge review verified exact one-stick partial receipts, immediate final completion, individual reward claims, clean one-time claimed state, and settings at comfortable/native scales. Fabric additionally reviewed the story choice and grey excluded branch. The normal native obtain packet observer was loaded on both clients; an outside-screen obtain receipt was not separately captured in the last screenshot (the enhanced vanilla root disables its vanilla notification).

Dedicated tests use two **simulated embedded connections**, not two actual remote clients. Read `runs/validation/{fabric,neoforge}/results-{initial,restart,independent}.json` for the current checks. Tests include the actual vanilla revoke command, repeated/reset-cycle claims, shared-party reset, other-member preservation, native sharing-ledger reset, branch award gating and prior progression/recovery/world-isolation cases.

Larger chapter banners, author-selectable milestone effects/sounds, animated card fades and more elaborate branch-line transitions remain future work. The current unlock line briefly highlights; there is no new chapter-title system. Larger-pack performance and two real remote clients remain unverified. No Minecraft version was added and nothing was committed or published.

Final dedicated results: **149 checks per loader** (125 initial,15 restart,9 independent),298 total, zero failures. Final Fabric and NeoForge shadowJar builds passed. The last adjacent-dot spacing change was compiled/packaged only; no further desktop input was used.

## Screenshots

Final horizontal row (the final1px gap removal is compiled after this capture):

![Horizontal row and contextual tooltip](../../runs/gui-review/juice-row-tooltip.png)

Delivery pulse and exact consumed-item receipt (intermediate marker layout):

![One-stick delivery receipt](../../runs/gui-review/juice-final-partial-receipt.png)

![Immediate completed frame and receipt](../../runs/gui-review/juice-final-completion.png)

![Claimed repeatable quest](../../runs/gui-review/juice-final-repeat-claimed.png)

![Settings at native GUI scale](../../runs/gui-review/juice-final-native-settings.png)
