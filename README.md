# FrameScope

Native Android screen recorder and universal media crop studio built with **Kotlin** and **Jetpack Compose** for **Android 13–16 (API 33–36)** on **ARM64 (`arm64-v8a`)** devices.

## Features

- **Full-Screen Recorder**: Hardware-first H.264 / H.265 encoding with automatic software fallback, Portrait & Landscape capture, configurable bitrate, and Internal Audio or Microphone capture (Mono AAC-LC 48 kHz / 128 kbps).
- **Device Capability Checks**: Automatic hardware/software capability badges (`✓ Supported` / `✕ Unsupported`) with automatic safe fallbacks across ARMv8 5G SoCs.
- **Universal Crop Tool**: Non-destructive image and video cropping with independent canvas & selection aspect ratios, touch zoom/pan, undo/redo/reset, live crop preview, custom bitrate/container export, and recent crop projects.
- **Recorded Videos Library**: In-app MP4 player, metadata inspector, gallery export, share, and deletion.

## Standard Android Studio Project Structure

```text
FrameScope/
├── .github/
│   └── workflows/                    # Preserved GitHub Actions auto debug APK workflows
│       ├── android-debug-apk.yml
│       └── build-apk.yml
├── gradle/
│   ├── libs.versions.toml            # Gradle Version Catalog (AGP 9.1.1, Kotlin 2.2.21, Compose BOM)
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties # Gradle 9.3.1 wrapper config
├── app/
│   ├── build.gradle.kts              # Module build config (minSdk 33, targetSdk 36, arm64-v8a)
│   ├── proguard-rules.pro            # R8 / ProGuard keep rules
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           ├── java/com/example/framescope/
│           │   ├── FrameScopeApplication.kt
│           │   ├── MainActivity.kt
│           │   ├── capability/       # DeviceCapabilityEngine
│           │   ├── crop/             # Crop models, media inspector, image & video processors
│           │   ├── data/             # DataStore settings & media repositories
│           │   ├── encoding/         # Foreground service, MediaCodec, MediaMuxer, audio/display capture
│           │   └── ui/               # Jetpack Compose screens, navigation, theme, welcome & crop UI
│           └── res/                  # Drawables, mipmap adaptive icons, values, values-night, layouts, xml
├── .gitignore                        # Standard Android Studio / Gradle ignore rules
├── build.gradle.kts                  # Root Gradle build script
├── settings.gradle.kts               # Project settings & :app module include
├── gradle.properties                 # Gradle JVM & AndroidX configuration
├── gradlew                           # Gradle Wrapper (Linux/macOS)
├── gradlew.bat                       # Gradle Wrapper (Windows)
└── debug.keystore.base64             # Preserved debug keystore backup
```

## Opening in Android Studio

1. Open **Android Studio** -> **File > Open** and select the root `FrameScope` folder.
2. Android Studio automatically generates `local.properties` pointing to your local Android SDK (SDK 36 / Build-Tools 36.0.0) and syncs Gradle using the included wrapper (`Gradle 9.3.1`).
3. If `debug.keystore` is not present locally, generate it once in the project root (or let the GitHub Actions workflow generate it automatically):
   ```bash
   keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```
4. Build via Android Studio **Run** or command line:
   ```bash
   ./gradlew :app:assembleDebug
   ```

