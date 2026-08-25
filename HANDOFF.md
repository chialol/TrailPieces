# Trail Pieces — session handoff

Last updated: 2026-08-24

Use this doc to onboard a fresh agent session. Gameplay rules live in **`:puzzle-engine`**; Compose + gestures in **`:app`**.

**Architecture (canonical — confirm all refactors against this):** [`docs/puzzle-architecture.md`](docs/puzzle-architecture.md)

---

## Architecture (do not collapse)

| Module | Responsibility |
|--------|----------------|
| **`:puzzle-engine`** | Pure JVM Kotlin — models, `PushService`, `DragSession`, `DragEngine`, `PlacementService`, `LockGroupService`, `ShuffleService`. All rules here. |
| **`:app`** | Compose UI, `PuzzleGame` facade, `PuzzleScreen` gestures. **No** push/lock/settle logic in UI. |

- **Manifest** (`PuzzleManifest.rows`) = solved puzzle height.
- **Playfield** (`SlotGrid.rows`) may grow taller mid-drag (insert-row / cutline). UI must draw **`drag.grid.rows`**, not only manifest height.
- **Persistent empties** = empty cells in rows that still have tiles (shown as `.` in debug). Not the same as lift hole (`_`).

---

## Core principles (product intent)

### 1. WYSIWYG settle — “what you see is what you get”

**Default:** The board at finger-up must match the mid-drag committed layout. On release, only **lock groups** recompute from final tile positions.

- Mid-drag **committed anchor** (`DragSession.committedAnchor`) is the truth for where the lift lands.
- `settlePreferred()` **baselines on `session.settlePlain()`** — park lifted tiles on committed holes, then collapse fully empty rows.
- Do **not** re-place from `originalBoard` in a way that shuffles resting tiles the user already saw.

**Blocked on release (when `committedAnchor ≠ startAnchor`):**

- **Latent home snap** — finger moved toward a tile’s correct slot but mid-drag committed elsewhere (e.g. S under Q at `(10,0)` while finger points home `(9,0)`). Old code scored +55 and rubber-banded; now guarded in `PlacementService.consider()`.

**Still allowed on release (explicit upgrades only):**

| Upgrade | Plain English | When |
|---------|---------------|------|
| **Cutline land** | Insert a blank row, repack below, land on correct slot | Finger clearly covers home; blocked landing needs row growth |
| **Lock-connect land** | Land on correct slot and join neighbors (e.g. H under A) | `lockCompletingJoinsKeptAbove` + finger covers home |
| **Completing-home swap** | Same-size swap that finishes both groups’ homes | Solves puzzle; finger toward home |
| **Finger-aim swap** | Off-axis swap onto aimed tile | Usually when mid-drag did not complete swap layout |

See `PlacementService.settlePreferred()` WYSIWYG guard (~line 433).

### 2. Locks commit on finger-up only

- **`DragSession.locks`** = lock partition **frozen at pointer-down** (`BoardService.beginDrag`).
- Mid-drag push / swap / make-way uses **`session.rigidLocks()`** only — not live `LockGroupService.compute`.
- **Accidental** correct adjacency mid-drag must **not** rigidize (no mega-groups, no peel of pointer-down groups).
- **Settle** recomputes locks from final geometry — new connections appear **after** release.

Tests: `LockCommitOnSettleTest.kt`

### 3. Mid-drag swap is visible; connection waits for release

- Same-size swaps should appear **while the finger is down** (partner in pickup spot, hole where they were).
- Partners stay **loose** mid-drag (frozen lock snapshot).
- On release, layout sticks; neighbors may **connect** if geometry matches home offsets.

**Mid-drag commit order** (`DragEngine.advancePushes`):

1. Completing-home swap  
2. **Aim-swap** (same-size onto occupied aim) — **before** aim-empty  
3. Aim-empty  
4. Axis push / empty-jump / tunnel  

**Partner parking for aim-swap** (`PlacementService.swapPartnerHoles`):

- Moved **≥ 2 cells** from pickup → partner goes to **original pickup footprint** (X onto K after down-then-left).
- Moved **1 cell** → partner uses **committed lift holes** (S under Q, U onto X).

Evacuate occupants from pickup cells before off-axis swap when needed (`evacuatePartnerHoles`).

### 4. Empty rows

- **Fully empty rows always collapse on settle** (`collapseEmptyRows()`). No spacer rows to “reserve” future locks.
- Locking is a **post-release** concern only.
- **Mid-drag row collapse** (WYSIWYG height during drag) is **not wired** — attempt broke hole/anchor remap; `SlotGrid.rowCollapseMap()` exists for future work.

### 5. Finger / gesture fidelity

- Lifted tiles follow **total finger delta** from drag start (never snap `fingerDeltaPx` to committed).
- Multi-cell tunnel/jump: look-ahead — finger must cover `(jump − 0.5) × cell` before commit (1-cell: `> 0.5`).
- Prefer **nearest** tunnel landing (`PushService.resolveNewHoles`).
- **Direction lock** within one `moveFinger` call.
- **UI order:** `endDrag()` **before** `clearFingerDelta()` (`PuzzleScreen.kt`) — settle must see real finger on release.

---

## Vocabulary (plain English)

| Term | Meaning |
|------|---------|
| **Home** | Where a tile belongs in the **solved** picture (`PuzzleTile.home`). |
| **Pickup footprint** | Cells where the lift started (`startAnchor` + shape offsets). |
| **Committed anchor** | Where the engine has committed the lift to land mid-drag. |
| **Lift hole** | `targetSlots` — cells of committed footprint; debug `_`. |
| **Cutline land** | Make room on correct slot by inserting a row and pushing mass below. |
| **Lock-connect** | Land on correct slot and snap to correctly aligned neighbors. |
| **Persistent empty** | `.` — empty cell in a row that still has other tiles. |

---

## Key files

| File | Role |
|------|------|
| `DragEngine.kt` | Gesture loop, commit order, trace, `endDrag` → `settlePreferred` |
| `DragSession.kt` | `locks`, `targetSlots`, `tryPush`, aim-swap/empty, `settlePlain()` |
| `PlacementService.kt` | Settle scoring, WYSIWYG guard, swap partner holes, cutline/home |
| `PushService.kt` | Axis push, make-way, tunnel, same-size swap along axis |
| `BoardService.kt` | `beginDrag` freezes locks |
| `LockGroupService.kt` | Lock compute (home-offset adjacency) |
| `BoardDebug.kt` / `DragTrace.kt` | Debug dump + trace ring buffer |
| `PuzzleScreen.kt` | Draw `restingGrid`, gesture → `PuzzleGame` |
| `PuzzleGame.kt` | Facade, `debugDump` |

---

## Test map (priority regressions)

| Test class | Covers |
|------------|--------|
| **`SettleHonorsCommittedTest`** | S under Q, zero/weak/strong finger toward home, scrambled `originalBoard`, empty-hole slide |
| **`LockCommitOnSettleTest`** | No mid-drag rigidize; locks commit on settle; no peel |
| **`SettlePreservesMidDragLocksTest`** | Zero-finger settle must not peel lock strip |
| **`UXEmptyParkSettleTest`** | U onto X — X parks on empty, not dumped on release |
| **`FingerAimSameSizeSwapTest`** | Mid-drag swap visible (down-then-left); settle layout |
| **`SwapPartnerHolesTest`** | Pickup vs committed partner holes |
| **`WHomeCutlineDumpTest` / `UHomeCutlineDumpTest`** | Cutline on release |
| **`CompletingSameSizeSwapTest`** | C↔J, BDF↔KMO solve on release |
| **`CollapseEmptyRowOnSettleTest`** | Empty row collapse policy |

**Workflow for bugs:** reproduce in `puzzle-engine/src/test` → fix engine → `.\gradlew.bat :puzzle-engine:test` → then `assembleDebug` for device feel.

**Mini board vocabulary:**

```
A B
C D
E F
```

(`LetterSlots`, `PuzzleFixtures`)

---

## Debug trace

`PuzzleGame.debugDump` includes drag trace (oldest → newest):

- `start` — lift + holes (start pinned if ring wraps)
- `commit:*` — push, aim-empty, aim-swap, empty-jump, home-swap, …
- `release-before-settle` — `committed=… finger=…` + grid with `_` holes
- `release-after-settle` — final board + row count

Compare **[n] release-before-settle** vs **[n+1] release-after-settle**. Resting tile positions should match except: lifted tile parked on `_`, locks recomputed, fully empty rows collapsed.

---

## Known bugs / follow-ups

1. **Left-then-down vs down-then-left** — indirect gesture paths may shove swap partner before aim-swap; down-then-left shows swap mid-drag reliably; left-then-down may only correct on settle. Reserving pickup footprint for whole drag would fix.
2. **Mid-drag row collapse** — not wired; playfield height may differ mid-drag vs after settle when empty rows involved.
3. **Singleton orphan empties** (e.g. `Q . / S .`) — collapse only removes **fully** empty rows; product decision whether to pack singleton holes on settle.

---

## Commands

```bat
cd android
.\gradlew.bat :puzzle-engine:test
.\gradlew.bat :app:assembleDebug
```

`JAVA_HOME` = Android Studio JBR if `java` not on PATH.

---

## Recent canonical bug (S under Q)

**Symptom:** Mid-drag `Q A / _ U` (committed `(10,0)`); release `S .` with U/V/T shuffled; or S snaps home `(9,0)`.

**Cause:** `settlePreferred` scored home snap from pre-drag board despite committed away from start.

**Fix:** WYSIWYG baseline + block latent home upgrade when `home == anchor && anchor != committedAnchor` (unless cutline / lock-connect / completing). Test: `strongFingerTowardHome_committedUnderQ_sticksPlain` with finger `Vec2(-469.5f, -117.75f)`.

---

## Agent rules

See `.cursor/rules/trail-pieces-puzzle.mdc` for always-on workflow (TDD, finger twitch checklist, lock pitfalls).

**Architecture plan (canonical — confirm every iteration):** [`docs/ARCHITECTURE-PLAN.md`](../docs/ARCHITECTURE-PLAN.md)
