# Trail Pieces

A cozy nature puzzle game inspired by [Art of Fauna](https://theartof.app/fauna/), built around photography of the natural world.

## Repository layout

```
trailPieces/
├── android/     # Android app (Kotlin + Jetpack Compose)
├── backend/     # Future API / services (placeholder)
├── shared/      # Shared content schemas and assets metadata (placeholder)
└── docs/        # Design notes and documentation
```

**Puzzle engine architecture** (park-only release, mid-drag owns motion):  
[`docs/puzzle-architecture.md`](docs/puzzle-architecture.md) · session handoff [`HANDOFF.md`](HANDOFF.md)

## Getting started (Android)

1. Open **Android Studio** → **Open** → select the `android/` folder.
2. Wait for Gradle sync to finish (first sync may take a few minutes).
3. Connect your phone via USB with **Developer options → USB debugging** enabled.
4. Select your device in the toolbar and click **Run** (green play button).

See [android/README.md](android/README.md) for detailed setup and troubleshooting.

## Adding puzzle images

1. Drop a portrait photo in **`shared/source/`** (e.g. `shared/source/trail.jpg`).
2. Run the chop script — see [tools/chop_puzzle/README.md](tools/chop_puzzle/README.md).
3. Rebuild/run the Android app. Tiles land in `android/app/src/main/assets/puzzles/default/`.
