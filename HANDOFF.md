# Trail Pieces — session handoff

Last updated: 2026-08-24

Use this to onboard a fresh agent session. Gameplay rules live in **`:puzzle-engine`**; Compose + gestures in **`:app`**.

**Canonical architecture (confirm every change against this):**  
[`docs/puzzle-architecture.md`](docs/puzzle-architecture.md)

**Agent always-on rules:** [`.cursor/rules/trail-pieces-puzzle.mdc`](.cursor/rules/trail-pieces-puzzle.mdc)

---

## Architecture (do not collapse)

| Module | Responsibility |
|--------|----------------|
| **`:puzzle-engine`** | Pure JVM Kotlin — models, mid-drag motion, park-only settle, locks, shuffle. All rules here. |
| **`:app`** | Compose UI, `PuzzleGame` facade, `PuzzleScreen` gestures. **No** push/lock/settle logic in UI. |

- **Manifest** (`PuzzleManifest.rows`) = solved puzzle height.
- **Playfield** (`SlotGrid.rows`) may grow mid-drag (insert-row). UI must draw **`drag.grid.rows` / resting grid height**, not only manifest height.
- **Persistent empties** = empty cells in rows that still have tiles (debug `.`). Lift hole = `_`.

---

## Core principles (product)

### 1. Park-only release (what you see is what you get)

Finger-up **never** rewrites resting tiles.

- Mid-drag **`committedAnchor`** is where the lift lands.
- `SettleService` / `DragSession.settle` / `endDrag` → **`ParkLifted` only** (park lift on holes → collapse empty rows → recompute locks).
- **No** settle cutline, completing-swap, lock-connect, finger-aim re-place, score loop, or `originalBoard` replay.

If a finish should exist, it must already be visible **mid-drag**.

Compare debug: `release-before-settle` vs `release-after-settle` — resting positions match except lift parked on `_`, locks recomputed, fully empty rows collapsed.

### 2. Locks commit on finger-up only

- **`DragSession.frozenLocks`** = partition frozen at pointer-down (`beginDrag`).
- Mid-drag uses **`session.rigidLocks()`** (`FrozenLockGraph`) only — never live `LockGroupService.compute`.
- Accidental adjacency mid-drag does **not** rigidize.
- **`ParkLifted`** recomputes locks from final geometry.

Tests: `LockCommitOnSettleTest.kt`

### 3. Swaps and pushes are mid-drag

- Same-size / completing-home swaps should appear **while the finger is down**.
- Partners stay loose mid-drag (frozen locks); connections appear after park.

**`DragEngine.advancePushes` order (approx.):** completing-home swap → aim-swap → aim-empty → axis push / tunnel.

Partner parking for aim-swap: see `MidDragMotion.swapPartnerHoles` (pickup vs committed holes).

### 4. Empty rows

- Fully empty rows **collapse on settle** (`collapseEmptyRows()`).
- Mid-drag row collapse is **not wired** yet (`SlotGrid.rowCollapseMap()` exists for later).

### 5. Finger / gesture fidelity

- Lift follows **total finger delta** from drag start (never snap `fingerDeltaPx` to committed).
- Multi-cell look-ahead: residual `> (jump − 0.5) × cell` (1-cell: `> 0.5`).
- Prefer **nearest** tunnel landing.
- Direction lock within one `moveFinger` call.
- UI: `endDrag()` **before** clearing finger delta so settle sees the real finger (park ignores finger for layout, but traces/debug still need it).

---

## Vocabulary

| Term | Meaning |
|------|---------|
| **Home** | Solved slot (`PuzzleTile.home`). |
| **Pickup footprint** | Start cells (`startAnchor` + shape). |
| **Committed anchor** | Mid-drag land position for the lift. |
| **Lift hole** | `targetSlots` — debug `_`. |
| **Persistent empty** | `.` — empty in a non-empty row. |
| **Park** | Release: fill holes with lift; no resting shuffle. |

---

## Key files

| File | Role |
|------|------|
| `DragEngine.kt` | Gesture loop, mid-drag commit order, `endDrag` → `SettleService` |
| `MidDragMotion.kt` | All layout commits while finger down |
| `PushService.kt` / `AxisMotion.kt` | Axis push, make-way, tunnel, axis swap |
| `FrozenLockGraph.kt` | Pointer-down lock snapshot |
| `DragSession.kt` | Snapshot; delegates to `MidDragMotion`; `settle` → park |
| `ParkLifted.kt` / `SettleService.kt` | Release = park only |
| `PlacementService.kt` | Legacy finger math / predicates (shrink) |
| `BoardService.kt` | `beginDrag` freezes locks |
| `LockGroupService.kt` | Lock compute at rest |
| `BoardDebug.kt` / `DragTrace.kt` | Debug dump + trace |
| `PuzzleScreen.kt` / `PuzzleGame.kt` | Draw + facade |

---

## Test map (priority)

| Test class | Covers |
|------------|--------|
| **`SettleHonorsCommittedTest`** | Park honors mid-drag (S under Q, strong finger, empty slide) |
| **`SettleBoundaryTest` / `SettleWysiwygGuardTest`** | Settle ≡ `ParkLifted` |
| **`LockCommitOnSettleTest`** | No mid-drag rigidize; locks on park |
| **`UXEmptyParkSettleTest`** | Mid-drag park sticks on release |
| **`FingerAimSameSizeSwapTest`** | Mid-drag swap visible |
| **`CompletingSameSizeSwapTest`** | Mid-drag completing swap + park (some multi-tile cases `@Ignore`) |
| **`PushThroughConnectedComponentTest`** | Tunnel / insert-row mid-drag; park on release |
| **`CollapseEmptyRowOnSettleTest`** | Empty row collapse |

**Ignored on purpose (wrong spirit or mid-drag not ready):** settle cutline dumps (`U`/`W`/`CorrectPlacement`), some `ComponentPlacementPolicy` prefer-landing settle finishers. Do **not** re-enable by restoring release upgrades.

**TDD:** fail a unit test first → fix engine → `.\gradlew.bat :puzzle-engine:test` → then device.

Mini board:

```
A B
C D
E F
```

---

## Debug trace

`PuzzleGame.debugDump` — oldest → newest:

- `start`, `commit:*`, `release-before-settle`, `release-after-settle`

Resting tiles must match before/after settle except park + lock recompute + empty-row collapse. Any other jump = architecture violation.

---

## Known follow-ups

1. Mid-drag completing swap gaps (e.g. BDF↔KMO, some diagonal SwapCH) — fix in **`MidDragMotion`**, not settle.
2. Mid-drag empty-row collapse.
3. Shrink `PlacementService` / `ReleaseUpgrade` dead settle code.
4. Indirect gesture paths (left-then-down vs down-then-left) for partner parking.

---

## Commands

```bat
cd android
.\gradlew.bat :puzzle-engine:test
.\gradlew.bat :app:assembleDebug
```

`JAVA_HOME` = Android Studio JBR if `java` not on PATH.
