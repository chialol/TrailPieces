# Trail Pieces — architecture plan (canonical)

Last updated: 2026-08-24

**Use this doc every iteration.** Before merging or claiming fixed, confirm changes match this plan. If code needs guards/scoring/penalties to enforce a principle, the architecture is wrong — fix the boundary instead.

---

## Product principles (user experience)

1. **WYSIWYG release** — What you see while dragging is what you get when you let go. Resting pieces do not jump, shuffle, or snap home on release unless you clearly asked for an explicit upgrade (below).

2. **Locks commit on finger-up** — Pieces do not bond mid-drag from accidental alignment. Groups picked up at pointer-down stay rigid mid-drag. New connections appear only after release.

3. **Swaps visible mid-drag** — Trades with another piece happen under your finger, not only after release.

4. **Finger owns motion** — Lifted pieces follow total finger delta; no jump-ahead, no sliding under the thumb.

5. **Empty rows collapse** — Fully empty rows disappear on settle (and eventually mid-drag when wired).

---

## Layer model (one-way dependencies)

```
Models (SlotGrid, PuzzleManifest)
    ↓
FrozenLockGraph          ← pointer-down snapshot; members() only mid-drag
LockGraph                ← compute() only at rest / after park
    ↓
MidDragMotion            ← ALL layout commits while finger is down
AxisMotion + PushService ← axis push / swap / empty (no PlacementService)
    ↓
ParkLifted               ← DEFAULT release; park lift + collapse + locks
ReleaseIntentDetector    ← read-only; picks at most one upgrade type
CompletingSwapUpgrade    ← explicit opt-in upgrade
CutlineHomeUpgrade       ← explicit opt-in upgrade
    ↓
DragEngine.endDrag       ← orchestrator only
PuzzleGame / PuzzleScreen ← facade + draw; NO rules
```

**Forbidden:** scoring loops, generic `tryPlace(anchor)` on release, replay from `originalBoard`/`fresh` unless inside an isolated cutline upgrade with a documented reason.

---

## Release pipeline (target — the only allowed shape)

```kotlin
fun settle(session, finger, ...): PuzzleBoard {
    val parked = ParkLifted.apply(session)   // ALWAYS runs

    return when (ReleaseIntentDetector.detect(session, finger, ...)) {
        ReleaseIntent.Plain -> parked

        is ReleaseIntent.CompletingSwap ->
            CompletingSwapUpgrade.tryApply(parked, session, intent) ?: parked

        is ReleaseIntent.CutlineHome ->
            CutlineHomeUpgrade.tryApply(parked, session, intent) ?: parked

        // No FingerAim / FingerAimTarget re-place when committed away from start
    }
}
```

### Rules that make mistakes impossible

| Principle | Enforcement |
|-----------|-------------|
| Plain release = no resting tile moves | `ParkLifted` is the only default return; it cannot call swap/cutline/pack |
| No release optimizer | **No** `scoreBoard`, penalties, or `maxBy` over candidates |
| Upgrades are opt-in | Detector returns **one** typed intent or `Plain`; each upgrade is its own object |
| Mid-drag ≠ release | `ReleaseUpgrade` / cutline / swap-on-release **cannot** be called from `MidDragMotion` |
| Frozen locks mid-drag | `PushService` / `MidDragMotion` accept `FrozenLockGraph` only — no `LockGroupService.compute` |
| Locks on release | `LockGroupService.compute` only in `ParkLifted` and upgrade finalize steps |

---

## Mid-drag pipeline (target)

- **`DragSession`** — snapshot only (grid, holes, frozen locks, anchors). No `tryPush` on the type; optional thin delegates for tests.
- **`MidDragMotion`** — `tryPush`, aim-swap, aim-empty, insert-row, completing-home-swap mid-drag.
- **`FrozenLockGraph`** — frozen at `beginDrag`; `members(tileId)` only.
- **Partner holes for aim-swap** — adjacent aim → committed holes; non-adjacent / indirect path → pickup footprint.

---

## What exists today vs plan

### Done (aligned)

| Item | Location |
|------|----------|
| `ParkLifted` | `puzzle-engine/.../ParkLifted.kt` |
| `FrozenLockGraph` | `puzzle-engine/.../FrozenLockGraph.kt` |
| `MidDragMotion` | `puzzle-engine/.../MidDragMotion.kt` |
| `AxisMotion` (PushService cycle broken) | `AxisMotion.kt`, `PushService.kt` |
| `ReleaseIntent` + detector skeleton | `ReleaseIntent.kt`, `ReleaseIntentDetector.kt` |
| `SettleService` entry from `DragEngine.endDrag` | `DragEngine.kt` |
| Boundary tests | `SettleBoundaryTest`, `MidDragBoundaryTest`, `SettleWysiwygGuardTest` |
| Left-then-down mid-drag swap fix | `MidDragMotion.swapPartnerHoles` |
| 164 JVM tests passing (before partial WYSIWYG guard work) | `:puzzle-engine:test` |

### Not done (plan violations — remove, don't guard)

| Violation | Current location | Fix |
|-----------|------------------|-----|
| Scoring loop picks release layout | `SettleService.settle` | Delete; use `when (intent)` only |
| Generic `ReleaseUpgrade.tryPlace(anchor)` | `ReleaseUpgrade.kt` | Split into typed upgrades |
| `fresh` / `originalBoard` replay on release | `SettleService.rebuildFreshSession` | Only inside `CutlineHomeUpgrade` if needed |
| `SettleWysiwygGuard` / score penalties | `SettleWysiwygGuard.kt`, `PlacementService` penalties | **Delete** after pipeline is correct |
| `PlacementService` still holds settle + finger + mid-drag delegates | `PlacementService.kt` | Slim to finger math + move detection helpers |
| Mid-drag row collapse | not wired | Future; document in HANDOFF bugs |
| UI lift snap vs committed anchor | `PuzzleScreen` draws at `startSlot + fingerDelta` | Separate visual fidelity task |

---

## Iteration checklist (run every time)

Before claiming done:

- [ ] Does plain release call **only** `ParkLifted` (no upgrade path taken)?
- [ ] Is there **any** score loop or candidate competition on release? → remove it
- [ ] Can any release code path move resting tiles without going through a **named** upgrade type?
- [ ] Does mid-drag motion use **only** `FrozenLockGraph`?
- [ ] Did we add a "guard" instead of removing capability? → wrong; fix boundary
- [ ] `./gradlew.bat :puzzle-engine:test` green
- [ ] Optional: `:app:assembleDebug` for device

---

## Test strategy

- **Boundary tests** — architecture (plain release = park; frozen locks; no finger-aim when committed)
- **Scenario tests** — S-under-Q, U-on-X, cutline dumps, completing swap (existing suite)
- **Do not** add penalty/score tests; add tests that prove the API **cannot** take the wrong path

---

## Known open product bugs (not architecture)

- Mid-drag row collapse not wired (height may jump at release)
- Singleton orphan empty cells — product decision pending

---

## Related docs

- `HANDOFF.md` — session onboarding, test map, debug trace
- `.cursor/rules/trail-pieces-puzzle.mdc` — agent TDD workflow

---

## Commands

```bat
cd android
.\gradlew.bat :puzzle-engine:test
.\gradlew.bat :app:assembleDebug
```

`JAVA_HOME` = Android Studio JBR if Gradle fails on Java 8.
