# Trail Pieces — puzzle engine architecture (canonical)

Last updated: 2026-08-24

**This doc is the source of truth.** Every refactor iteration must confirm changes against it.
Operational handoff (bugs, tests, commands): [`HANDOFF.md`](../HANDOFF.md).

---

## User principles → architectural rules

| What the player should feel | Rule enforced in code |
|----------------------------|------------------------|
| What I see while dragging is what I get on release | **`SettleService` = `ParkLifted` only.** Finger-up never rewrites resting tiles. |
| Every move happens before lift | Push / swap / tunnel / completing-home swap live in **`MidDragMotion`** only. |
| Pieces don't bond mid-drag | **`FrozenLockGraph`** at pointer-down; no live `LockGroupService.compute` in push. |
| Locks connect after finger-up | **`LockGroupService.compute`** only in **`ParkLifted`**. |
| Finger owns the motion | Look-ahead in **`MidDragMotion`** / **`DragEngine`**; lift follows total finger delta. |

**There is no release “upgrade” path.** Cutline / completing-swap / finger-aim on settle were deleted. If a finish isn’t visible mid-drag, it doesn’t happen.

---

## Layer diagram (one-way dependencies)

```
┌─────────────────────────────────────────────────────────────┐
│  :app — PuzzleScreen, PuzzleGame (facade only, no rules)    │
└────────────────────────────┬────────────────────────────────┘
                             │
┌────────────────────────────▼────────────────────────────────┐
│  DragEngine — gesture loop, MidDragMotion + Settle          │
└─────┬───────────────────────────────────────────────┬───────┘
      │ mid-drag                                         │ release
      ▼                                                  ▼
┌──────────────────┐                          ┌──────────────────────┐
│  MidDragMotion   │                          │  SettleService       │
│  + PushService   │                          │    → ParkLifted only │
│  + AxisMotion    │                          │                      │
│  + FrozenLockGraph                          └──────────┬───────────┘
└──────────────────┘                                     ▼
      │                                       ┌──────────────────────┐
      ▼                                       │  LockGroupService    │
┌──────────────────┐                          │  (compute at rest)   │
│  SlotGrid models │                          └──────────────────────┘
└──────────────────┘
```

**Forbidden:** UI rules, score loops, release finishers, `LockGroupService.compute` inside push, replaying `originalBoard` on settle.

---

## Module map (`:puzzle-engine`)

| File / object | Layer | CAN do | CANNOT do |
|---------------|-------|--------|-----------|
| **`SlotGrid`, models** | Model | Hold tile positions | Rules |
| **`FrozenLockGraph`** | Locks | `members()` from pointer-down | `compute()`, mutate |
| **`LockGroupService`** | Locks | `compute(grid)` at rest | Used mid-drag in motion |
| **`AxisMotion`** | Mid-drag | Axis swap, empty jump | Settle |
| **`PushService`** | Mid-drag | Axis push, make-way, tunnel | Call settle |
| **`MidDragMotion`** | Mid-drag | All commits while finger down | Settle, recompute locks |
| **`DragSession`** | Snapshot | Hold grid, holes, frozen locks | Contain rule logic (delegate) |
| **`ParkLifted`** | Release | Park lift on holes, collapse empty rows, compute locks | Move resting tiles |
| **`SettleService`** | Release | Call `ParkLifted` | Any other layout mutation |
| **`PlacementService`** | Legacy shrink | Finger math / mid-drag predicates | Grow; no settle finishers |
| **`ReleaseUpgrade`** | Legacy | Helpers still used by mid-drag predicates | Called from settle |

---

## Release pipeline (the contract)

```kotlin
fun settle(session, ...): PuzzleBoard = ParkLifted.apply(session)
```

### Hard rules

1. **Release never moves resting tiles** — only fills committed holes with the lift, then collapse + lock compute.
2. **No score loop, no intent resolver, no named release upgrades.**
3. **No `originalBoard` replay on settle.**
4. If a behavior should exist, it must commit in **`MidDragMotion`** before finger-up.

---

## Mid-drag pipeline

```
DragEngine.moveFinger
  → MidDragMotion.tryPush / tryFingerAim* / tryCompletingHome* / tryNearestEmpty
    → PushService (FrozenLockGraph)
    → AxisMotion (swap / empty jump)
```

Locks frozen at `PuzzleBoard.beginDrag` → `FrozenLockGraph.freeze`.

---

## Anti-patterns (do not reintroduce)

| Anti-pattern | Why it's wrong | Instead |
|--------------|----------------|---------|
| Release upgrades / cutline on settle | Board jumps after finger-up | Mid-drag commit, then park |
| Score loop on release | Silent non-WYSIWYG | Deleted |
| `SettleWysiwygGuard` | Patches a bad API | No capability to override park |
| `tryPlace` on settle | Shuffles resting tiles | `ParkLifted` only |
| `LockGroupService.compute` in push | Mid-drag rigidize | `FrozenLockGraph` only |

---

## Iteration checklist

- [ ] `SettleService.settle` is **only** `ParkLifted.apply`.
- [ ] No new release finishers.
- [ ] Mid-drag uses **`FrozenLockGraph`**.
- [ ] New motion has a unit test (mid-drag assert + release equals park).
- [ ] `./gradlew.bat :puzzle-engine:test` passes.
- [ ] This doc updated if boundaries change.

---

## Implementation status

| Item | Status |
|------|--------|
| `ParkLifted` + mid-drag split | ✅ Done |
| Release = park only (upgrades deleted) | ✅ Done |
| Settle cutline dump tests ignored | ✅ U / W / CorrectPlacement |
| Completing swap tests = mid-drag + park | ✅ Rewritten |
| `ReleaseUpgrade` leftover (mid-drag helpers) | ⚠️ Shrink later |
| `FingerIntent` extracted | ⬜ Not started |

---

## Test commands

```bat
cd android
.\gradlew.bat :puzzle-engine:test
.\gradlew.bat :app:assembleDebug
```
