# Prep reveal (v3)

Turns a high-res photo plus ordered pieces into a phone-sized scene. The app
never ships the original file. Output height is capped at **1920px** (width at
4096px) as lossy WebP quality 85.

## Mass-output loop

1. Put `photo.jpg` in `shared/source/reveal/<id>/`. `photo-bw.jpg` can wait until the finish command. It has to be the same pixel size as the photo.
2. Seed `meta.json` (title, scenery, one mood, blurb) and put the photo on a trail for that place in `shared/source/catalog/trails.json`, creating the trail when none exists. That step does not cut pieces and does not install anything.
3. Serve the tracer and cut there. Save into the scene folder.

```powershell
.\tools\trace_pieces\serve.ps1
```

Leave that window open. In the page, use Cut mode and the pen (`P`). **Open folder** on `shared/source/reveal/<id>` and save. That writes `pieces/` and `meta.json` next to `photo.jpg`.

A downloaded zip is tracer output: `meta.json`, `pieces/01.png`, `pieces/paths.json`. Unzip it **into** `shared/source/reveal/<id>` so those files sit next to `photo.jpg`. A sibling folder named `<id>-pieces` is the wrong place. `01.png` is a valid piece name. `paths.json` is only for reopening outlines; prep ignores it.

Open photo loads seeded metadata when the page is served from the repo. A `file://` page cannot see the folder beside the photo.

4. Add `photo-bw.jpg` next to `photo.jpg`.
5. From the repo, prep the scene:

```powershell
.\tools\finish_reveal.ps1 <id>
```

`finish_reveal.ps1` runs this prep script (title from `meta.json`) and does not edit `trails.json`. When the photo id is already in a trail's `photoIds`, it then runs `import_catalog`. When it is not, the assets are still written, and the command says the photo will not appear in **Walk a trail** until the id is added and `finish_reveal` (or `import_catalog`) is run again.

Assets are baked into the APK at build time. Reloading an already installed app does not show the new photo. Install from Android Studio and open **Walk a trail**.

## Where to drop the photo

```
shared/source/reveal/mountain/
  photo.jpg                 # full color, portrait or landscape
  photo-bw.jpg              # dark plate, SAME pixel size as photo.jpg
  pieces/
    01-meadow.png           # full frame, SAME pixel size, strip order from the left
    02-river.png
```

`photo.jpg` can also be named after the folder (`mountain.jpg`). The dark plate
is `<that filename>-bw.jpg` (`photo-bw.jpg` or `mountain-bw.jpg`).

### Piece format

Preferred: **PNG with real transparency**, one file per piece, canvas identical
to the photo. Opaque where the piece is, transparent everywhere else. Number
them from left to right in the strip. `01.png` is valid. A word
after the number is the on-screen name: `01-meadow.png` sits first in the strip and
is labeled "meadow". Any piece can be played.

Also accepted: JPEG full frames whose kept pixels match `photo.jpg` and whose
unused area is near-white (same idea as the Death Valley layer exports).

Pieces do not need to cover the whole photo. Uncovered areas stay on the dark
plate until every piece is placed, then the color photo fades in.

### Tracing pieces

[tools/trace_pieces](../trace_pieces/index.html) replaces the Photoshop mask step.
Serve it with `.\tools\trace_pieces\serve.ps1`, open the scene folder, and cut.
Cut mode slashes the photo apart, so the pieces stay a complete cover. The pen
is `P`. Scroll zooms, Space pans. Save with Open folder, or unzip the download
into that same folder. `pieces/paths.json` is only for reopening outlines; this
script ignores it. Then run `.\tools\finish_reveal.ps1 <id>`.

## Setup

Reuses the chop_puzzle venv:

```powershell
cd C:\src\2026\natureGame
python -m venv tools\chop_puzzle\.venv
tools\chop_puzzle\.venv\Scripts\pip install -r tools\prep_layers\requirements.txt
```

## Usage

```powershell
.\tools\finish_reveal.ps1 mountain
```

That reads the title from `meta.json` and runs:

```powershell
.\tools\chop_puzzle\.venv\Scripts\python .\tools\prep_reveal\prep_reveal.py mountain --title "Mountain"
```

Assets land in
`android/app/src/main/assets/reveal/mountain/`:

| File | Use |
|------|-----|
| `plate.webp` / `alive.webp` | Dark starting photo and full-color finish |
| `cover.webp` | Scene picker thumbnail |
| `crop/piece_NN.webp` | Tight crop drawn where the piece locks |
| `lift/piece_NN.webp` | Crop with a soft outline and shadow, drawn while dragging |
| `icon/piece_NN.webp` | Small lift image for the waiting row |

**Walk a trail** shows the photo when its id is in a trail's `photoIds` and
`import_catalog` has written `catalog.json`. This prep script writes the scene
files and does not edit either. Install from Android Studio and open **Walk a trail**.

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `scene` | | Folder name under `shared/source/reveal/` |
| `--title` | folder name | Display title |
| `--max-height` | `1920` | Longest phone-sized height |
| `--max-width` | `4096` | Cap for very wide panoramas |
| `--full-res` | off | Skip downsizing |
| `--quality` | `85` | WebP quality |
