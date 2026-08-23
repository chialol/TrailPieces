# Trail Pieces — handoff

## Where we are (2026-08-23)

- Puzzle logic lives in `android/puzzle-engine` with JVM unit tests.
- Push-through, rigid groups, insert-row (Up when blocked), collapse empty rows are implemented.
- App module is Compose UI + thin `PuzzleGame` facade.
- **Finger / tunnel commit:** `DragEngine` peeks `tryPush` and only commits when residual `> (jumpCells - 0.5) × cell` (so a 2-cell tunnel through `(A,C)` waits ~1.5 cells, not 0.5). `fingerDeltaPx` stays true finger travel. Mid-drag reverse uses the same look-ahead (no catch-up lock).

## If continuing work

1. New chat in this repo (fresh context).
2. Point at `.cursor/rules/trail-pieces-puzzle.mdc` (always-on).
3. For behavior changes: write/adjust tests in `PushThroughConnectedComponentTest` / `DragEngineTest` first.
4. Run `:puzzle-engine:test` before device builds.

## Do not re-litigate without tests

- Rigid peel vs shift vs insert-row — covered by push-through tests.
- Play-again / shuffle — `ShuffleService` + `enforceRigidLocks = false`.
- Late tunnel + reverse — `verticalPair_EDoesNotShiftPairUntilFingerNearLandingAbove`, `verticalPair_canReverseTunnelShiftWithoutLiftingFinger`.
