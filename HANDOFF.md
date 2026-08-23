# Trail Pieces — handoff

## Where we are (2026-08-23)

- Puzzle logic lives in `android/puzzle-engine` with JVM unit tests.
- Push-through, rigid groups, insert-row (Up when blocked), collapse empty rows are implemented.
- App module is Compose UI + thin `PuzzleGame` facade.
- **Finger tracking:** lifted tiles use true cumulative `fingerDeltaPx` (never snapped to committed). After a push that leaves committed ahead of the finger, reverse pushes are suppressed until catch-up (`awaitCatchUpAlong`) — fixes permanent offset under the finger that `clampFingerToCommitted` caused.

## If continuing work

1. New chat in this repo (fresh context).
2. Point at `.cursor/rules/trail-pieces-puzzle.mdc` (always-on).
3. For behavior changes: write/adjust tests in `PushThroughConnectedComponentTest` / `DragEngineTest` first.
4. Run `:puzzle-engine:test` before device builds.

## Do not re-litigate without tests

- Rigid peel vs shift vs insert-row — covered by push-through tests.
- Play-again / shuffle — `ShuffleService` + `enforceRigidLocks = false`.
- Finger under lift / no reverse while behind — `DragEngine.awaitCatchUpAlong` (not clamping `fingerDeltaPx`).
