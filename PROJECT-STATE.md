# DJI Unchained VOC — Project State

Last updated: 2026-08-28

This is the short, authoritative project brief. Read it before planning or changing the app. Use the linked documents only when more detail is needed.

## Identity

| Item | Current value |
|---|---|
| Product | DJI Unchained VOC |
| Purpose | Offline Android USB live view and recording for a DJI Goggles N3 video stream without DJI Fly |
| Current development version | 0.7.2 |
| Latest hardware-tested version | 0.7.1 |
| Android application ID | `local.n3view.voc` |
| Debug application ID | `local.n3view.voc.debug` |
| Minimum Android version | API 29 |
| Target/compile Android version | API 36 |
| Primary test phone | Rooted/unlocked Android 16 LineageOS phone |
| Primary DJI hardware | DJI Goggles N3 with DJI O4 Pro / DJI Avata 2 |
| Repository | <https://github.com/jtm2kfpv-git/DJI-Unchained-VOC> |
| License | Apache-2.0 for code and documentation; branded artwork excluded |

## Current capabilities

- Receives the Goggles N3 stream through Android USB accessory mode.
- Parses the DJI LogicLink framing and decodes the incoming H.264 video locally.
- Provides `FIT`, `FILL`, and `STRETCH` display modes.
- Provides `AUTO`, `16:9`, and `4:3` source-aspect selection.
- Provides a positionable 9:16 Shorts crop with experimental 720×1280 MP4 recording.
- Provides selectable 30 or 60 FPS Shorts recording.
- Records the original stream as lossless MP4 by default.
- Provides raw H.264 original-stream recording as an Advanced fallback.
- Stores automatic original recordings in `Movies/DJI Unchained VOC`.
- Shows the project artwork while disconnected and keeps responsive controls at the bottom.
- Exports privacy-filtered diagnostic schema 3 data.
- Supports manual recovery, auto reconnect, and keep-awake controls.

## Privacy and safety boundary

- The app declares no Internet, location, camera, microphone, contacts, accounts, or broad-storage permission.
- MediaProjection is requested only when starting a Shorts screen recording.
- Video processing and diagnostic generation are local to the Android device.
- The project is unofficial, is not affiliated with DJI, and must not be used as a flight-safety display.

## Verification baseline

### v0.7.1

- Successfully tested on the target phone and DJI Goggles N3.
- Supplied diagnostics showed stable 1920×1080 video with no parser resynchronizations, recovery actions, or raw-recorder drops.

### v0.7.2

- GitHub Android verification passes.
- 48 unit tests pass.
- Debug and release lint pass.
- Debug and unsigned release APK assembly pass.
- Root source matches `versions/v0.7.2` across the guarded release files.
- APK identity, permissions, and debug v2 signature were verified.

## Pending validation

v0.7.2 has not completed its on-device regression test. The following paths remain the priority:

1. Lossless original-stream MP4 recording and playback.
2. Raw H.264 fallback recording.
3. Shorts recording at both 30 and 60 FPS.
4. Preview stability while original-stream recording is active.
5. Disconnected top artwork and bottom-control layout on the target phone.
6. Diagnostic schema 3 export after each recording mode.

Until those tests pass, v0.7.1 remains the hardware-tested fallback and v0.7.2 remains an alpha prerelease.

## Source-of-truth rules

- Repository root contains the current development source.
- `versions/vX.Y.Z/` contains immutable source snapshots of earlier versions.
- APKs and release source ZIPs belong on GitHub Releases, not in Git history.
- Do not delete or overwrite an earlier version when starting a new one.
- Update this file, `NEXT-RELEASE.md`, `TESTING.md`, `VERSIONS.md`, and `CHANGELOG.md` when a release state changes.
- A version must pass the release-sync check before publication.

## Standard desktop gate

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
python .\tools\verify_release_sync.py
```

For detailed evidence, see [BUILD-VERIFICATION.md](BUILD-VERIFICATION.md). For published history, see [VERSIONS.md](VERSIONS.md) and [CHANGELOG.md](CHANGELOG.md).
