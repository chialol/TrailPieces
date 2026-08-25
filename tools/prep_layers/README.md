# Prep layers

Turns segmented photo exports into stackable RGBA assets for the layers mode.

Defaults match chop_puzzle: **max width 1080**, **lossy WebP quality 85** — looks
sharp on a phone without shipping oversized files.

## Source layout

Put a matching set in `shared/source/`:

```
shared/source/deathvalley.jpg      # optional full reference (recommended)
shared/source/deathvalley-1.jpg    # segment 1 (full frame, white cutout)
shared/source/deathvalley-2.jpg
...
shared/source/deathvalley-5.jpg
```

Current Death Valley exports are **JPEG full frames**: the kept segment matches the
photo; the unused area is near-white (JPEG cannot store transparency). The script
rebuilds a real alpha channel.

True RGBA PNGs with embedded alpha are also accepted.

## Setup

Reuses the chop_puzzle venv:

```powershell
cd C:\src\2026\natureGame
python -m venv tools\chop_puzzle\.venv
tools\chop_puzzle\.venv\Scripts\pip install -r tools\chop_puzzle\requirements.txt
```

## Usage

```powershell
.\tools\chop_puzzle\.venv\Scripts\python .\tools\prep_layers\prep_layers.py deathvalley --title "Death Valley"
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `stem` | `deathvalley` | Looks for `{stem}-1`, `{stem}-2`, … |
| `--title` | stem title-cased | Display title in `layers.json` |
| `--max-width` | `1080` | Scale canvas width (`0` / `--full-res` keeps original) |
| `--format` | `webp` | `webp` (default) or `png` |
| `--quality` | `85` | WebP quality 1–100 |
| `--white-threshold` | `245` | Cutout detection if no reference photo |
| `--ref-tolerance` | `18` | Match-to-reference tolerance |
| `--feather` | `0.8` | Soften alpha edges |
| `--muted-sat` | `0.35` | Tray-chip saturation (undeveloped look) |

## Output

```
android/app/src/main/assets/layers/deathvalley/
├── layers.json
├── preview.webp
├── layers/
│   ├── layer_01.webp     # full-canvas RGBA (aligned)
│   └── ...
└── tray/
    ├── layer_01.webp     # tight crop, optionally muted
    └── ...
```

Layers stay **full-canvas and aligned** so the app can stack them without
reposition math. Tray crops are for the undeveloped chip strip.

## Saturation / color — where it lives

| Concern | Where | Why |
|---------|--------|-----|
| Live scrub during play (sat / warmth) | **Android runtime** (`ColorMatrix` / Compose `ColorFilter`) | Instant, reversible, no asset explosion |
| Undeveloped tray chip look | **Optional Python** (`--muted-sat`) | Fixed preview; one file per layer |
| Final “print” look | The prepared RGBA layers as authored | Destination image |

Do **not** pre-generate many saturation steps in Python for gameplay. Bake mute
only for tray chrome; let the finger scrub color on-device.

## Mask strategy

1. If `{stem}.jpg` (or png/webp) exists → opaque where layer ≈ reference.
2. Else → opaque where pixels are not near-white cutout fill.
3. Optional Gaussian feather on alpha for softer edges.
