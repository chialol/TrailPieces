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

## Adding layered scene images

1. Drop `{stem}.jpg` (optional) plus `{stem}-1.jpg` … `{stem}-N.jpg` in **`shared/source/`**.
2. Run [tools/prep_layers/README.md](tools/prep_layers/README.md).
3. Rebuild/run. Assets land in `android/app/src/main/assets/layers/{stem}/`.

The app home menu includes **Walk a trail**, **Trail puzzle**, **Develop a print**, and **Reveal a photo**.

## Adding a photo for Walk a trail

1. Put `photo.jpg` in **`shared/source/reveal/<id>/`**.
2. Seed `meta.json` for that folder. That is the only agent step. If a trail was named, the photo id is added to that trail's `photoIds` in `shared/source/catalog/trails.json` at the same time.
3. Cut in the tracer (`.\tools\trace_pieces\serve.ps1`) and save into that same folder. Unzip a downloaded zip there too, next to `photo.jpg`.
4. Add `photo-bw.jpg` (same pixel size), then from the repo:

```powershell
.\tools\finish_reveal.ps1 <id>
```

That preps the scene. When the photo id is already on a trail, it also imports the catalog. Install from Android Studio and open **Walk a trail**. If the id is not on a trail, the photo will not appear there until it is added to a trail's `photoIds` and this command (or [import_catalog](tools/import_catalog/README.md)) is run again. Details: [tools/prep_reveal/README.md](tools/prep_reveal/README.md).
