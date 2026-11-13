# Repeated navigation and paused-menu fixes — October 22

The disappearing dots, missing branch shading, unloaded quest details and stuck story choices shared a confirmed networking failure. The request budget replenished using player ticks. Singleplayer menus pause those ticks, so repeated navigation exhausted the budget indefinitely. Rejected requests were silently dropped. Separately, the client discarded its tree presentation while the server could suppress an identical replacement snapshot.

The budget now uses monotonic elapsed time and returns explicit rejection feedback. Explicit tree requests always return a snapshot. The client keeps eight bounded tree caches and refreshes active tree/detail state every two seconds, including while menus pause the world. Dots retain the requested adjacent horizontal row at the upper-right.

Story choices now recover a missing acknowledgement through a correlated, read-only status request. They never automatically replay a choice or reward mutation. Locked branches remain readable and explain their restriction, with actions disabled. Retry Loading has a separate left footer, outside the reward column. Left-opening tooltip counters reserve the fixed right-side icon column.

## Actual NeoForge client review

Minecraft 26.1.2 NeoForge, owned world **Codex GUI Review NeoForge**. These are actual window captures, not mockups. No user test/dev save was opened. Review preferences were restored byte-for-byte after normal client shutdown.

- Switched between Chronicle and vanilla tabs **20 times** without returning to gameplay. Dots and branch shading remained present.
- Inspected an unselected story branch: its description, objectives and rewards loaded, its lock reason was visible, and actions were disabled.
- Made **12 complete story → details → tree → details → story round trips** while staying in menus, then selected the campkeeper branch. The server immediately acknowledged and saved the choice.
- Resized to a **517 × 301 client area**, forcing the statistic tooltip left and upward. Its `0 / 5` counter remained left of the icon without overlap.
- The final client log contained zero ERROR/FATAL entries.

![Dots and locked branches after twenty tab switches](../../runs/gui-review/navigation-after-20-tabs.png)

![An unselected branch loads its definition and explains its lock](../../runs/gui-review/navigation-locked-details.png)

![Choice saved after repeated paused-menu navigation](../../runs/gui-review/navigation-choice-after-stress.png)

![Left-opening narrow tooltip keeps progress clear of the icon](../../runs/gui-review/navigation-small-left-progress.png)

The first screenshots use a 1264 × 761 client area, Minecraft GUI scale 3 and Codex Comfortable scale 2. The last uses the smaller client area stated above.

## Reusable checks

```bat
python scripts/test-server.py all --reset --compat-dir runs/compat-mods
python build-smart.py compile
python build-smart.py shadowJar
```

Use Windows Java 25 for this repository. The test runner owns the disposable validation worlds selected by `--reset`.

Both Fabric and NeoForge passed **135 initial + 15 restart + 9 independent-world checks = 159 each, 318 total**. These server tests use simulated players and embedded network connections; they do not establish two real remote-client connections. They include existing progression, submission, rewards, parties, persistence and native Sagittary/Sleep Cycle compatibility scenarios.

The new regression invokes the same `QuestNetworking.handle` dispatch used by incoming client packets, captures Architectury-wrapped outbound payloads and decodes the actual S2C codecs. It issues 54 navigation requests with the player tick counter held constant, checks budget replenishment, repeated same-root refresh, locked-branch data, correlated reads, successful subsequent choice acknowledgement, read-only choice recovery and explicit missing-ID errors.

Reports: `runs/validation/{fabric,neoforge}/results-{initial,restart,independent}.json`.

Both distribution jars packaged successfully. They contain the new request budget and updated quest screens, and exclude validation classes and demo datapack resources. Final sizes: Fabric **426,407 bytes**, NeoForge **427,079 bytes**. All changes remain uncommitted and unstaged.

## Validation limits

The repeated-navigation and small-tooltip GUI regression was exercised in a real NeoForge client. Fabric passed compilation, packaging and the server suite; this follow-up did not repeat its GUI review. Deliberate packet loss was not injected into the real client. The timeout/status-reload controls were checked in code, and correlated read recovery was exercised in the server suite. Two real remote clients and large-pack performance remain separate follow-ups.

Review screenshots and disposable test reports are ignored local artifacts, not bundled mod content.
