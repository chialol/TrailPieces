# Reveal a photo (v3): session handoff

Last updated: 2026-10-04. Everything below is committed (`c3855a4`).

This mode is separate from the trail-puzzle engine. `HANDOFF.md` and `docs/puzzle-architecture.md` cover the grid puzzle; none of the `:puzzle-engine` rules apply here.

## What the mode is

1. The player picks a scene (Mountain or Mossy Rock) from a picker of cover cards.
2. A dark version of the photo fills the play area. Landscape photos fill the height and the player pans sideways by dragging the photo. Portrait photos fit inside the area and never pan.
3. Pieces wait as small icons in a row at the top, in filename order. Only the first one is draggable; the rest are dimmed. There is no hint text, title, or counter.
4. Dragging the icon lifts the piece. The touch point slides to the piece's center within 140 ms while it is still icon-sized. From then on the center stays under the finger, and the piece grows to full size once the finger is over the photo. Wide pieces may hang off the screen.
5. A release within `SnapThresholdDp` (20dp) of the right spot locks the piece. It slides into place and its outline fades into the photo. A miss animates the piece back to its icon.
6. After the last piece: a soft flash, a small bouncy zoom, the full-color photo fades in, then a glossy shine sweeps across. The top row turns into a replay button.

## Files

App code: `android/app/src/main/java/com/trailpieces/app/layers/v3/`

| File | Role |
|------|------|
| `RevealScreen.kt` | Scene picker, play screen, drag overlay, settle and finale animations. All tuning constants are at the top. |
| `RevealSession.kt` | Pure placement rules: order, `tryPlace` snap test, `anchoredTopLeft` (keeps the grab point under the finger), `PhotoFit` (landscape vs portrait sizing). |
| `RevealBitmaps.kt` | Decodes one scene's images once, as GPU bitmaps, at most 4 at a time. |
| `RevealLoader.kt` / `RevealModels.kt` | Read `scene.json`; every folder in `assets/reveal/` becomes a scene. |
| `RevealFeedback.kt` | `onCorrectRelease` / `onSceneAlive` hooks for future haptics or sound. Silent today. |

Unit tests: `android/app/src/test/kotlin/com/trailpieces/app/layers/v3/RevealSessionTest.kt`

Routing: the home menu's **Reveal a photo** goes straight to `RevealScreen` (`TrailPiecesApp.kt`) and still shows the scene picker. **Walk a trail** opens one scene with `initialSceneId` and records completion through `onCompleted` (see [`journey-architecture.md`](journey-architecture.md)). `LayersScreen` still has a `REVEAL_V3` chip, which is now redundant.

## Assets

Raw photos live in `shared/source/reveal/<scene>/` and are **git-ignored**: they are 3–70 MB each. Expected layout:

```
shared/source/reveal/mossyrock/
  photo.png            # full color (photo.jpg also works)
  photo-bw.png         # dark plate, same pixel size
  pieces/
    01-log.png         # full frame, same pixel size, transparent outside the piece
    02-log2.png
```

The number in a piece's filename is its play order. A new scene or new photos need a regenerate:

```powershell
cd C:\src\2026\natureGame
.\tools\chop_puzzle\.venv\Scripts\python .\tools\prep_reveal\prep_reveal.py mossyrock --title "Mossy Rock"
.\tools\chop_puzzle\.venv\Scripts\python .\tools\prep_reveal\prep_reveal.py mountain --title "Mountain"
```

Each scene takes 1–3 minutes. Output goes to `android/app/src/main/assets/reveal/<scene>/` (committed, about 10 MB for both scenes):

- `plate.webp` and `alive.webp`: the dark and color photos, height capped at 1920px.
- `cover.webp`: the picker thumbnail.
- `crop/`: tight crops, drawn where each piece locks.
- `lift/`: crops padded with an ivory outline and soft shadow, drawn while dragging.
- `icon/`: small lift images for the top row.

The outline and shadow are baked in by the script, not drawn by the app.

## Build

Gradle needs JDK 17+. The default `java` on this machine is 8, so point at Android Studio's:

```powershell
cd C:\src\2026\natureGame\android
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:testDebugUnitTest --tests com.trailpieces.app.layers.v3.RevealSessionTest
.\gradlew.bat :app:assembleDebug
```

Install on the phone from Android Studio.

## Decisions to keep

- **Finger movement must not recompose the screen.** The finger position and animation values are only read inside `Canvas` draw lambdas. Rebuilding the screen on every move was the main cause of the earlier sluggish drag.
- **Placed pieces draw as tight crops on one photo canvas**, not as full-photo transparent images.
- **The dragged piece uses its own full-resolution lift image.** Icons are separate files. An earlier version shared one cached image between them, which made the drag look blurry.
- **Small drag sizes draw the icon** and crossfade to the lift as it grows, so the outline doesn't break up when shrunk.
- **No `shadowElevation` on pieces.** It draws a rectangular shadow, which was the "rectangle border" complaint.

## Tuning history

- **Snap tolerance:** 112dp, then 56, 28, and now 20dp at the user's request. 20dp is about a third of a fingertip, and the finger hides the target.
- **Grab point:** first stayed wherever the user touched the icon. Now it is pinned to the bounding-box center.

## Known gaps and ideas

- **Bounding-box center:** for an irregular piece, the center can fall in an empty gap. Pinning to the center of the filled area is an option if that feels off.
- **No feedback yet:** haptics or sound for a correct release have hooks in `RevealFeedback` but nothing plays.
- **Release near the top edge:** the part of a big piece above the photo vanishes instantly instead of sliding in, because the settle animation is clipped to the photo.
- **Rotation:** restarts the round and decodes all images again.
- **Memory:** the mountain scene holds about 120 MB of graphics memory while playing. Loading `alive.webp` only before the finale would trim it.
- **No auto-pan:** dragging a piece toward the edge of a landscape photo doesn't scroll it. The user pans before or after placing.
