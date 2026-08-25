# Architecture plan — superseded

**Canonical doc:** [`puzzle-architecture.md`](puzzle-architecture.md)

This file previously described a settle pipeline with named release upgrades
(`CompletingSwap`, `CutlineHome`, intent resolver). That model was **removed**.

Current contract:

- Mid-drag owns **all** layout motion (`MidDragMotion`).
- Release is **`ParkLifted` only** — finger-up never rewrites resting tiles.

Do not resurrect release finishers, score loops, or `originalBoard` settle replay.
Update [`puzzle-architecture.md`](puzzle-architecture.md) when boundaries change.
