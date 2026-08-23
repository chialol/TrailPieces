# Chop puzzle

Turns a source photo into sliding-puzzle tiles for the Android app (Fauna-style image slicing).

Tiles always cover the **full image** — no cropping. For a grid of `cols × rows`:

- tile width = image width ÷ cols  
- tile height = image height ÷ rows  

Safe to **re-run** anytime; it replaces the previous output in `android/app/src/main/assets/puzzles/{id}/`.

## Setup (once)

```powershell
cd C:\src\2026\natureGame
python -m venv tools\chop_puzzle\.venv
tools\chop_puzzle\.venv\Scripts\pip install -r tools\chop_puzzle\requirements.txt
```

## Usage

1. Drop a **portrait** image in `shared/source/` (e.g. `shared/source/trail.jpg`).
2. Run:

```powershell
tools\chop_puzzle\.venv\Scripts\python tools\chop_puzzle\chop.py
```

Or pass a specific file and grid:

```powershell
tools\chop_puzzle\.venv\Scripts\python tools\chop_puzzle\chop.py shared\source\deathvalley.jpg --cols 2 --rows 12
```

### Options

| Flag | Default | Description |
|------|---------|-------------|
| `--id` | `default` | Puzzle folder name in Android assets |
| `--cols` | auto (3 portrait / 4 landscape) | Grid columns |
| `--rows` | auto (4 portrait / 3 landscape) | Grid rows |
| `--title` | filename | Display title in the app |

Output lands in:

```
android/app/src/main/assets/puzzles/{id}/
├── puzzle.json
└── tiles/
    ├── tile_0_0.png
    ├── tile_0_1.png
    └── ...
```

3. Rebuild/run the app in Android Studio. The home screen opens the puzzle when assets are present.

## Grid defaults

| Orientation | Grid | Pieces |
|-------------|------|--------|
| Portrait (taller) | 3 × 4 | 12 (11 image + 1 empty slot) |
| Landscape (wider) | 4 × 3 | 12 |

Override with `--cols` and `--rows` for any aspect ratio (e.g. 2 × 12 for a tall panorama).
