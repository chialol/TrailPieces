# Trail Pieces — Android

Kotlin + Jetpack Compose app. Open this folder (`android/`) in Android Studio.

## Prerequisites

- [Android Studio](https://developer.android.com/studio) (Ladybug or newer recommended)
- A physical Android device with USB debugging enabled, **or** an emulator

Android Studio bundles its own JDK (17+). You do not need to configure Java separately.

## Open the project

1. Launch **Android Studio**.
2. **File → Open** → navigate to this repo's `android/` folder and click **OK**.
3. If prompted to trust the project, click **Trust Project**.
4. Wait for **Gradle Sync** to complete (status bar bottom-right). First sync downloads dependencies and may take several minutes.

## Run on your phone

1. On your phone: **Settings → About phone** → tap **Build number** 7 times to enable Developer options.
2. **Settings → Developer options** → enable **USB debugging**.
3. Connect the phone via USB. Accept the **Allow USB debugging** prompt on the phone.
4. In Android Studio, your device should appear in the device dropdown (top toolbar).
5. Click the green **Run** button (or **Run → Run 'app'**).

The app installs as **Trail Pieces** with a green leaf icon.

## Build from the command line (optional)

From the `android/` folder:

```powershell
.\gradlew.bat assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk`

Install directly if `adb` is on your PATH:

```powershell
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Troubleshooting

| Issue | Fix |
|-------|-----|
| Gradle sync fails / SDK not found | **File → Settings → Languages & Frameworks → Android SDK** — install **Android 15 (API 35)** SDK Platform and **Android SDK Build-Tools**. |
| Device not listed | Check USB cable, confirm debugging prompt on phone, try **Run → Device Manager** to verify connection. |
| `local.properties` missing | Android Studio creates this automatically on first open. It points to your SDK at `%LOCALAPPDATA%\Android\Sdk`. Do **not** commit this file. |
| JDK errors | **File → Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JDK** — select **Embedded JDK** or **jbr-17** from Android Studio. |

## Adding images

1. Drop a portrait photo in **`shared/source/`** at the repo root.
2. Run the chop script:

```powershell
tools\chop_puzzle\.venv\Scripts\python tools\chop_puzzle\chop.py
```

(See [tools/chop_puzzle/README.md](../tools/chop_puzzle/README.md) for first-time Python setup.)

3. Rebuild/run the app. Output goes to `app/src/main/assets/puzzles/default/`.
