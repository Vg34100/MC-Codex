# Local source checkpoints

The existing repository points to `Vg34100/MC-Codex`. On November 12, the user requested active Git tracking after filesystem problems. The previous local commit was October 19 (`d5bbffe`); newer implementations had remained uncommitted or untracked.

A local recovery branch, `codex/ports-and-recovery-2026-11-12`, checkpoints the maintained source, assets, examples, pack definitions, build configuration, documentation and tests. It deliberately includes unfinished port work so that it is recoverable. A checkpoint is not a release or a claim that all registered targets work.

World saves, runtime profiles, logs, recordings, build caches, generated JARs, local IDE configuration and credentials are excluded. The source snapshot is inspected for zero-filled text files and invalid JSON before committing. Corrupt originals are retained under ignored `runs/multiversion-baseline/corrupt-source-20261112/`. Known intact backups are used only when every surviving byte matches.

Use small local commits at meaningful development milestones, with the tested scope recorded in `SESSION_PROGRESS.md`. Do not push, tag or publish without the user's authorization.

To inspect previous source without changing the working tree:

```text
git log --oneline --decorate -10
git show <commit>:path/to/file
```

Git commits protect against accidental edits and deletions. A copy of the recovery bundle is also stored on C: under the user's local `MC-Codex-backups` directory, separate from the A: workspace. Keep it until a newer independent backup is confirmed. Neither this local checkpoint nor its bundle has been pushed to GitHub.

## Verified recovery results

The source checkpoint is `ffd2acf` (413 files). A full Git integrity check passes. Two damaged historical objects were restored from the existing GitHub repository and verified against their original object hashes. One damaged object was proven unreachable from every branch, reflog and the index; its original bytes were preserved outside the object database. Damaged-object copies remain on C: in `AppData/Local/MC-Codex-backups/damaged-git-objects-20261112/`. Normal dangling objects were left alone.

The verified complete-history bundle is `C:\Users\video\AppData\Local\MC-Codex-backups\MC-Codex-recovery-20261112.bundle`. Its adjacent JSON file records the commit and SHA-256. Nothing was pushed.
