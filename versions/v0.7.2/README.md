# DJI Unchained VOC 0.7.2 — Recording and layout iteration

Experimental, privacy-minimal Android receiver for the wired live-view output of DJI Goggles N3 with DJI O4 Air Unit Pro or DJI Avata 2.

> **Alpha software:** v0.7.2 builds on the hardware-tested v0.7.1 receiver, but its new recording paths still require device testing. Keep the preserved v0.7.1 APK available as a fallback.

## What v0.7.2 changes

- Fixes the original-stream recording preview flicker by removing the Android document-picker round trip from the Record button.
- Saves original recordings directly to `Movies/DJI Unchained VOC` through Android MediaStore.
- Makes **lossless MP4** the default original-stream format: incoming DJI H.264 access units are remuxed without re-encoding and receive monotonic arrival-time timestamps.
- Retains **Raw H.264** as an Advanced setting for troubleshooting and maximum compatibility.
- Starts an original recording only after SPS, PPS and an IDR keyframe are available.
- Shows Logo v3 at the top of the disconnected screen and keeps the responsive control tray anchored at the bottom.
- Replaces the Shorts **30/50/60 fps** selector with a clear **30/60 fps** toggle.
- Uses the surface H.264 encoder for Shorts MP4 capture, requests the selected maximum encoder rate and corrects non-monotonic output timestamps.
- Adds diagnostic schema 3 fields for original/Shorts frame timing, actual recorded FPS, timestamp corrections, dropped recording access units and video-surface losses during original recording.

No v0.7.1 source or APK is modified.

## Operation

### Original incoming stream

1. Connect the goggles and wait for `USB ✓ VIDEO ✓`.
2. Leave `Output: Original stream` selected.
3. In Advanced settings, choose `Original format: Lossless MP4` (default) or `Raw H.264`.
4. Tap **Record original MP4** or **Record raw H.264**. No file picker opens.
5. Tap **Stop and save**. Android publishes the completed file in `Movies/DJI Unchained VOC`.

The MP4 path preserves the incoming compressed image quality. Raw H.264 has no container timestamps, so some desktop players may guess the wrong speed; use the MP4 default for ordinary playback and editing.

### 9:16 Shorts

1. Select `Output: 9:16 Shorts` and rotate the Android device to portrait.
2. Drag the video horizontally to position the center crop.
3. Choose **Record FPS: 30** or **Record FPS: 60**.
4. Tap **Record Shorts MP4**, choose a destination and approve Android's screen-capture prompt.
5. Stop from the control tray or the foreground-service notification.

Shorts capture re-encodes the 720×1280 viewport. The selected value is a maximum output rate; actual FPS depends on the incoming stream, decoder, encoder and device load.

## Privacy

- No `INTERNET`, location, camera, microphone, account, contacts or broad storage permission.
- No analytics, advertising, telemetry, crash-reporting SDK, updater or DJI account integration.
- Android requests one-time MediaProjection consent only for a Shorts recording.
- Original MP4/H.264 recording does not use MediaProjection and does not show a file picker.
- USB video, recordings, artwork and diagnostics remain local.
- Root is not requested or required.

## Build

The project targets Android API 36 and Java 17:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Artifacts are copied to `output/apk/`. The debug APK is ready to sideload. The release APK is deliberately unsigned so users can inspect and sign it independently.

This project is unofficial and is not affiliated with, endorsed by or supported by DJI. Do not rely on it for flight safety, navigation or regulatory compliance. Bench-test with propellers removed and provide cooling where required.
