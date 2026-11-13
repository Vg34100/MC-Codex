# Quest transaction recovery

Inventory consumption, item/random rewards, treasure bags, overflow storage and XP use a world journal at `<world>/codex/transaction.json`. A transaction receipt is saved in the same vanilla player document as inventory/XP. All random output is generated before mutation. The server verifies the saved receipt before finishing the sidecars or acknowledging the transaction.

On a subsequent action or player join:

- The saved receipt matches the transaction: finish its sidecar updates, without granting items/XP or rolling loot again.
- The saved receipt matches the previous receipt: abort the unsaved action; inventory did not commit. The player can retry.
- Neither receipt matches, or files cannot be read/written: fail closed and retain the record for operator recovery. Make a whole-world backup before changing pending records or backups manually.

A single server-thread journal serializes inventory transactions, so an unresolved state write cannot be bypassed by another claim/submission. Full inventories store overflow in `codex/mailbox/<uuid>.json`. **Collect Stored Rewards** in quest details transfers it when room is available. Paid delivery criteria are reconciled after reconnect.

Recipe unlocks are idempotent and can be retried. Arbitrary reward functions may affect blocks, other players or external systems. `codex/reward_effects/<uuid>.json` marks a function running before execution; interrupted running/needs_review entries are not automatically replayed. Operators must inspect what actually happened before reconciling those entries. This is not a general rollback system for arbitrary functions or a guarantee against hardware/filesystem failure.

The authored `codex/quests.json` stores stable reward IDs and the original index order. The editor carries identities through edits/reordering. World/per-cycle `codex/claim_keys/<uuid>.json` supplements existing index ledgers; old ledgers remain readable. Manual advanced edits must retain the corresponding reward identity mapping if existing entitlements should stay unchanged.

The reusable server suite tests committed/unsaved receipt boundaries with constructed pending records, no duplicate grant on replay, malformed/unwritable state rejection, legacy claims/reordering, overflow collection and normal dedicated-server restarts. It does not claim an actual process-kill/power-loss test or two real connected clients.
