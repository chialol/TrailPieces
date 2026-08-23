# Trail Pieces — handoff

## Where we are (2026-08-23)

- Puzzle logic lives in `android/puzzle-engine` with ~78 JVM unit tests.
- Push-through, rigid groups, insert-row (Up when blocked), collapse empty rows, and finger-clamp after tunnel jumps are implemented.
- App module is Compose UI + thin `PuzzleGame` facade.
- Latest user-facing concern: **drag twitching** when shifting connected components — mitigated by clamping finger to committed after multi-cell pushes; verify on device after rebuild.

## If continuing work

1. New chat in this repo (fresh context).
2. Point at `.cursor/rules/trail-pieces-puzzle.mdc` (always-on).
3. For behavior changes: write/adjust tests in `PushThroughConnectedComponentTest` / `DragEngineTest` first.
4. Run `:puzzle-engine:test` before device builds.

## Do not re-litigate without tests

- Rigid peel vs shift vs insert-row — covered by push-through tests.
- Play-again / shuffle — `ShuffleService` + `enforceRigidLocks = false`.
- Twitch — `DragEngine.clampFingerToCommitted` + nearest tunnel landing.
