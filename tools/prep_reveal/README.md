# Prep reveal (v3)

Turns a high-res photo plus ordered pieces into a phone-sized scene. The app
never ships the original file. Output height is capped at **1920px** (width at
4096px) as lossy WebP quality 85.

## Where to drop the photo

```
shared/source/reveal/mountain/
  photo.jpg                 # full color, portrait or landscape
  photo-bw.jpg              # dark plate, SAME pixel size as photo.jpg
  pieces/
    01-meadow.png           # full frame, SAME pixel size, play order
    02-river.png
```

`photo.jpg` can also be named after the folder (`mountain.jpg`). The dark plate
is `<that filename>-bw.jpg` (`photo-bw.jpg` or `mountain-bw.jpg`).

### Piece format

Preferred: **PNG with real transparency**, one file per piece, canvas identical
to the photo. Opaque where the piece is, transparent everywhere else. Number
them in the order the player should receive them. A word after the number is
the on-screen name: `01-meadow.png` is offered first and labeled "meadow".

Also accepted: JPEG full frames whose kept pixels match `photo.jpg` and whose
unused area is near-white (same idea as the Death Valley layer exports).

Pieces do not need to cover the whole photo. Uncovered areas stay on the dark
plate until every piece is placed, then the color photo fades in.

## Setup

Reuses the chop_puzzle venv:

```powershell
cd C:\src\2026\natureGame
python -m venv tools\chop_puzzle\.venv
tools\chop_puzzle\.venv\Scripts\pip install -r tools\prep_layers\requirements.txt
```

## Usage

```powershell
.\tools\chop_puzzle\.venv\Scripts\python .\tools\prep_reveal\prep_reveal.py mountain --title "Mountain"
```

Rebuild the app after that. Assets land in
`android/app/src/main/assets/reveal/mountain/`.

Open **Reveal a photo** on the home screen.

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `scene` | | Folder name under `shared/source/reveal/` |
| `--title` | folder name | Display title |
| `--max-height` | `1920` | Longest phone-sized height |
| `--max-width` | `4096` | Cap for very wide panoramas |
| `--full-res` | off | Skip downsizing |
| `--quality` | `85` | WebP quality |
