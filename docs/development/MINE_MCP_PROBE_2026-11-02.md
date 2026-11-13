# Mine MCP control probe — November 2, 2026

**Result: PASS, 11 assertions.** This is the bounded control test requested after the failed desktop-input recording approach. No desktop mouse movement, SendKeys, key injection or coordinate clicking was used.

## Actual runtime

- Minecraft 26.1.2, Fabric Loader 0.19.5, Java 25, current Codex development sources.
- Mine MCP 0.2.0, exact published JAR SHA-256 `b28dfbd14d804ca5ed947a2f3eef2dae16a49a653949632ad3ee883929015fbd` verified before installation.
- Separate game/build outputs under `runs/mine-mcp-probe`; only a closed disposable test world was cloned. No user saves, normal loader settings or global MCP configuration changed.
- Authenticated local HTTP JSON-RPC using the instance's private connection file. Tokens are not printed or copied into the report.

## Checks passed

1. Identified the isolated JVM for a read-only focus check.
2. Mine MCP reported the actual 26.1.2 runtime and active world.
3. `get_screen` discovered the real Codex screen, Title field, current value and Save button.
4. Verified that the test game was in the background before editing.
5. `set_text` entered `Advancement test 123`; `get_screen` returned that exact value.
6. `click_widget` pressed the actual Save button; the server-written advancement JSON contained the exact title.
7. The ordinary vanilla tick criterion remained unchanged.
8. `close_screen` closed the editor through the client API.
9. Reopening through `send_command` retained the exact saved title in the GUI.
10. `take_screenshot` returned a real game-framebuffer PNG.
11. Read-only OS focus checks confirmed that all mutation actions completed while the test Minecraft client remained in the background.

The API calls targeted widget indices discovered from the current screen and included the expected screen class. The test did not call Codex save methods directly or write the resulting title to JSON itself.

## Evidence

- [Game-framebuffer proof](../../runs/mine-mcp-probe/proof.png)
- [Assertions and action responses](../../runs/mine-mcp-probe/results.json)
- [Before widgets](../../runs/mine-mcp-probe/before.json) / [after reopening](../../runs/mine-mcp-probe/after.json)
- [Exact local probe](../../runs/mine-mcp-probe/test-control.py)

Evidence and the downloaded mod remain ignored local artifacts, not production dependencies. The private connection file must stay untracked.

## Setup corrections and limits

The first disposable world had commands disabled, so Codex correctly rejected the non-privileged player's editor access despite a console-origin command returning success. That test client was closed normally, the cloned world's commands flag was enabled with a backup, and the test was restarted. No permissions were bypassed in Codex.

The new test window initially owned foreground focus. It was minimized and shown without activation using window-management calls before the background test; no mouse or keyboard input was sent. The actual edit/save/readback then passed in the background.

This proves direct field/button control and still-frame capture on this Fabric configuration. It does **not** prove smooth visible cursor movement, per-character typing, full branch/delivery workflows, custom canvas-node/tab navigation, continuous video capture, or NeoForge support. Mine MCP targets Fabric. A full walkthrough still needs those separate checks; no completed video is claimed.

Sources: [release receipt](https://github.com/hashk014/Mine-MCP/blob/main/publication.json), [connection/API guide](https://github.com/hashk014/Mine-MCP/blob/main/docs/CONNECT.md), [compatibility evidence](https://github.com/hashk014/Mine-MCP/blob/main/docs/COMPATIBILITY.md).
