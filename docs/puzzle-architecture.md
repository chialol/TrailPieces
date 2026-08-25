# Trail Pieces — puzzle engine architecture (canonical)

Last updated: 2026-08-24

**This doc is the source of truth.** Every refactor and bugfix must match it.
Operational handoff (bugs, tests, commands): [`HANDOFF.md`](../HANDOFF.md).
Agent workflow: [`.cursor/rules/trail-pieces-puzzle.mdc`](../.cursor/rules/trail-pieces-puzzle.mdc).

---

## Product principles → code rules

| What the player should feel | Rule enforced in code |
|----------------------------|------------------------|
| What I see while dragging is what I get on release | **`SettleService` = `ParkLifted` only.** Finger-up never moves resting tiles. |
| Every layout change happens before finger-up | Push / swap / tunnel / completing-home / aim live in **`MidDragMotion` only**. |
| Pieces don't bond mid-drag | **`FrozenLockGraph`** at pointer-down; no live `LockGroupService.compute` in motion. |
| Locks appear after release | **`LockGroupService.compute`** only inside **`ParkLifted`**. |
| Finger owns motion | Look-ahead in **`DragEngine`** / **`MidDragMotion`**; lift follows **total** finger delta. |

**There is no release “upgrade” / finisher path.** If a move isn’t visible mid-drag, it does not happen on lift. Reintroducing settle cutline, settle completing-swap, score loops, or `originalBoard` replay is a regression.

---

## Layer diagram (one-way dependencies)

```
┌─────────────────────────────────────────────────────────────┐
│  :app — PuzzleScreen, PuzzleGame (facade only, no rules)    │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│  DragEngine — gesture loop → MidDragMotion + SettleService  │
└─────┬───────────────────────────────────────────────┬───────┘
      │ mid-drag                                         │ release
      ▼                                                  ▼
┌──────────────────┐                          ┌──────────────────────┐
│  MidDragMotion   │                          │  SettleService       │
│  + PushService   │                          │    → ParkLifted only │
│  + AxisMotion    │                          └──────────┬───────────┘
│  + FrozenLockGraph                                     ▼
└────────┬─────────┘                          ┌──────────────────────┐
         ▼                                    │  LockGroupService    │
┌──────────────────┐                          │  (compute at rest)   │
│  SlotGrid models │                          └──────────────────────┘
└──────────────────┘
```

**Forbidden:** UI rules, score loops, release finishers, `LockGroupService.compute` inside push, settle replay of `originalBoard`.

---

## Module map (`:puzzle-engine`)

| File / object | Layer | CAN do | CANNOT do |
|---------------|-------|--------|-----------|
| **`SlotGrid`, models** | Model | Hold tile positions | Rules |
| **`FrozenLockGraph`** | Locks | `members()` from pointer-down | `compute()`, mutate |
| **`LockGroupService`** | Locks | `compute(grid)` at rest | Used mid-drag in motion |
| **`AxisMotion`** | Mid-drag | Axis swap, empty jump | Settle |
| **`PushService`** | Mid-drag | Axis push, make-way, tunnel | Call settle |
| **`MidDragMotion`** | Mid-drag | All layout commits while finger down | Settle, recompute locks |
| **`DragSession`** | Snapshot | Grid, holes, frozen locks; delegates motion | Own rule logic |
| **`ParkLifted`** | Release | Park lift on holes, collapse empty rows, compute locks | Move resting tiles |
| **`SettleService`** | Release | Call `ParkLifted` | Any other mutation |
| **`PlacementService`** | Legacy shrink | Finger math / mid-drag predicates | Grow; settle finishers |
| **`ReleaseUpgrade`** | Legacy | Helpers still used by mid-drag predicates | Called from settle |

---

## Release contract

```kotlin
fun settle(session, ...): PuzzleBoard = ParkLifted.apply(session)
```

1. Fill committed holes with the lift; collapse fully empty rows; recompute locks.
2. Resting tiles do not move.
3. No intent resolver, score loop, cutline, or swap-on-release.

`DragSession.settle()` and `DragEngine.endDrag()` use the same park-only path.

---

## Mid-drag pipeline

```
DragEngine.moveFinger
  → MidDragMotion.tryPush / tryFingerAim* / tryCompletingHome* / tryNearestEmpty
    → PushService (FrozenLockGraph)
    → AxisMotion (swap / empty jump)
```

Typical commit order in `DragEngine.advancePushes`:

1. Completing-home same-size swap  
2. Aim-swap (same-size onto occupied aim)  
3. Aim-empty  
4. Axis push / empty-jump / tunnel  

Locks frozen at `PuzzleBoard.beginDrag` → `FrozenLockGraph.freeze`.

---

## TDD workflow (required for logic)

1. **Write / extend a failing unit test** under `puzzle-engine/src/test` (prefer `LetterSlots` A–F mini board).
2. Prefer **two assertions** for motion features:
   - **Mid-drag:** layout already correct while finger is down.
   - **Release:** equals park of that session (no resting-tile jump).
3. Watch it fail; fix **engine** code (not Compose) until green.
4. Run `.\gradlew.bat :puzzle-engine:test` before claiming fixed.
5. Only then `assembleDebug` for gesture feel.

Do **not** jump to UI for locks, push, peel, tunnel, insert/collapse, or settle bugs.

If a test expects settle-time board rewrite (cutline dump, etc.), it is **out of spirit** — `@Ignore` with a reason, or rewrite as mid-drag + park. Do not “fix” by adding release finishers.

---

## Anti-patterns (do not reintroduce)

| Anti-pattern | Why | Instead |
|--------------|-----|---------|
| Release upgrades / cutline on settle | Board jumps after finger-up | Commit mid-drag, then park |
| Score / tier / “best layout” on release | Silent non-WYSIWYG | Deleted forever |
| `SettleWysiwygGuard` | Patches a bad API | No capability to override park |
| `tryPlace` / `originalBoard` on settle | Shuffles resting tiles | `ParkLifted` only |
| `LockGroupService.compute` in push | Mid-drag rigidize | `FrozenLockGraph` only |
| Rules in `PuzzleScreen` | Untested drift | `:puzzle-engine` tests |

---

## Iteration checklist

Before merging any settle/motion change:

- [ ] `SettleService.settle` is **only** `ParkLifted.apply`.
- [ ] No new release finishers.
- [ ] Mid-drag uses **`FrozenLockGraph`**, not live compute.
- [ ] New behavior has a unit test: mid-drag assert + release equals park.
- [ ] `.\gradlew.bat :puzzle-engine:test` passes.
- [ ] This doc updated if boundaries change.

---

## Follow-ups (not blockers)

| Item | Notes |
|------|--------|
| Shrink `ReleaseUpgrade` / `PlacementService` | Move mid-drag helpers; delete settle-era dead code |
| Extract `FingerIntent` | Finger → cell predicates out of `PlacementService` |
| Mid-drag empty-row collapse | Height parity while dragging (careful with hole remap) |
| Completing multi-tile home swap mid-drag | e.g. BDF↔KMO — some tests `@Ignore` until mid-drag covers it |

---

## Test commands

```bat
cd android
.\gradlew.bat :puzzle-engine:test
.\gradlew.bat :app:assembleDebug
```

`JAVA_HOME` = Android Studio JBR if needed.
