# Trail Pieces — handoff

## Where we are (2026-08-23 late)

- Placement policy is in `:puzzle-engine` (`PlacementService` + settle/collapse).
- `:puzzle-engine:test` is green (swap, empty-fit, lock-completing cutline, prior rigid/tunnel/insert-row).
- **Same-size past rigid mass**: when adjacent make-way/tunnel fails, `PushService` falls through to `PlacementService.tryNearestSameSizeSwapAlongAxis` (nearest congruent CC along the push axis). Finger look-ahead still gates multi-cell jumps. Dump case: singleton E down through a locked wall onto loose A.
- **Off-axis same-size swap**: mid-drag uses `tryFingerAimSameSizeSwap` when the finger projects diagonally (both row and col change from drag start), before axis push — so E→C beats on-axis E→A. On release, scored finger-aim vs home/cutline (no early return).
- **Home cutline (U dump)**: home landing uses cutline (not same-size swap) when `shouldCutlineHomeInsteadOfSwap` — landing-row neighbor must repack (e.g. L beside U). `sameSizeSwapCompletesHomes` keeps C↔H-style swaps when both tiles reach home. Cutline keepers only lock-connect upward (`isCutlineLandingRowKeeper`).

## Placement model (implemented)

1. **Empty-fit** — `PushService` early path (unchanged).
2. **Same-size CC swap** — adjacent congruent CCs exchange in `PushService` via `PlacementService.trySameSizeSwap`; if blocked by a larger rigid mass, nearest same-size further along the axis; non-adjacent home swaps at settle.
3. **Lock-completing / home land** — on `DragEngine.endDrag`, if the finger path aims at a blocked home, place from the **pre-drag** board: same-size swap or **cutline** (keep above + landing-row keepers, insert separator, column-major pack below).
4. **Settle collapse** — `collapseEmptyRowsPreservingLocks`: drop fully empty rows unless collapsing would newly vertically lock two tiles (keeps cutline separators).

## Commands

```bat
cd android
.\gradlew.bat :puzzle-engine:test
.\gradlew.bat :app:assembleDebug
```
