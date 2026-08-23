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

## Getting started (Android)

1. Open **Android Studio** → **Open** → select the `android/` folder.
2. Wait for Gradle sync to finish (first sync may take a few minutes).
3. Connect your phone via USB with **Developer options → USB debugging** enabled.
4. Select your device in the toolbar and click **Run** (green play button).

See [android/README.md](android/README.md) for detailed setup and troubleshooting.

## Adding puzzle images

Drop source images into `android/app/src/main/assets/images/`. The app will load them from there in a future update.
