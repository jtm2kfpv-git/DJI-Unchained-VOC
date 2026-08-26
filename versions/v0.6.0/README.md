# DJI Unchained VOC 0.6

Experimental, privacy-minimal Android receiver for the wired live-view output of DJI Goggles N3 with DJI O4 Air Unit Pro or DJI Avata 2.

## What v0.6 adds

- Source-aspect selector: **AUTO**, **16:9**, **4:3**, and **9:16 SHORTS**.
- Existing **FIT**, **FILL**, and **STRETCH** presentation modes remain available for normal aspect modes.
- Shorts mode uses a clipped 9:16 viewport, disables FIT/FILL/STRETCH, and blocks the unused image area.
- Drag horizontally on the Shorts video to choose the crop position; the position is saved locally.
- Experimental direct **720x1280 MP4** Shorts recording through Android's consent-gated MediaProjection API.
- Original lossless raw H.264 recording remains unchanged for normal recording.
- Logo v3 as the Android launcher icon and as the offline/disconnected background.
- The background disappears after a decoded frame and returns after disconnect or three seconds without a rendered frame.
- Setup progress for accessory detection, USB open, video packets, and rendered frames.
- Diagnostics now include Android/device information, decoder name, selected aspect, keep-awake state, and allocatable storage.
- User-selectable **Keep awake** behavior, enabled by default.
- Timestamped recording filenames and a 500 MB storage-safety gate for Shorts recording.

## Shorts recording

1. Select `Aspect: 9:16 SHORTS` and rotate the Android device to portrait.
2. Drag the visible video left or right to position the crop.
3. Tap **Record Shorts MP4**, select a destination, and approve Android's screen-capture prompt.
4. Hide the control panel while recording. Tap the video to reveal it again and stop, or use the notification's **Stop and save** action.

Shorts capture re-encodes the visible viewport and therefore uses more battery, heat, and processing power than raw H.264 recording. It is experimental until verified on the target phone. The normal raw recorder remains the quality-preserving fallback.

## Privacy

- No `INTERNET`, location, camera, microphone, account, contacts, or broad storage permission.
- No analytics, advertising, telemetry, crash-reporting SDK, updater, or DJI account integration.
- Android asks for one-time screen-capture consent before each Shorts recording.
- The media-projection foreground service exists only while a Shorts recording is active.
- USB video, raw recordings, MP4 capture, artwork, and diagnostics remain local.
- Root is not requested or required.

## Build

The project targets Android API 36 and Java 17:

```text
gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

The debug APK is ready to sideload. The release APK is deliberately unsigned so users can inspect and sign it independently.

This project is unofficial and is not affiliated with, endorsed by, or supported by DJI. Do not rely on it for flight safety, navigation, or regulatory compliance. Bench-test with propellers removed and provide cooling where required.
