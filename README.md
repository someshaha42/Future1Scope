# FrameScope

Native Android screen recorder built with Kotlin and Jetpack Compose for Android 16+ (ARM64 `arm64-v8a`).

## Features

- **Recording Modes**: Full Screen or hardware-aligned Region / Area capture with visual region editor and optional animated red capture frame preview.
- **Orientation Policies**: Dedicated Portrait and Landscape capture policies with dynamic `MediaProjection.Callback.onCapturedContentResize` handling.
- **Audio Capture**: Internal Audio (`AudioPlaybackCapture`) or Microphone capture (with automatic external USB/wired/Bluetooth microphone prioritization), encoded as Mono AAC-LC at 48 kHz / 128 kbps.
- **Bitrate Control**: Preset bitrates (100–350 Mbps) plus custom Mbps input persisted via Jetpack DataStore.
- **Encoding Pipeline**: Fixed H.264 VBR hardware encoding, 8-bit YUV 4:2:0, BT.709 (SDR) Limited 16–235 color metadata, 2-second GOP, native display-driven surface capture, and `MediaMuxer` MP4 output.
- **Recorded Videos Library**: In-app MP4 library with metadata inspection, playback, MediaStore gallery export, sharing, and deletion.

## Build

```bash
./gradlew :app:assembleDebug
```
