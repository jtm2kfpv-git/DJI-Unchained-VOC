# DJI Unchained VOC 0.7.4 build verification

Verification date: 2026-09-16

Status: **desktop and primary hardware workflow verified; prerelease published**

## Scope

Version 0.7.4 removes the complete experimental Shorts screen-capture path while preserving the working USB live view and original-stream recording paths.

- application ID: `local.djiunchained.voc`
- debug application ID: `local.djiunchained.voc.debug`
- version code: `11`
- version names: `0.7.4` and `0.7.4-debug`
- application label: `DJI Unchained VOC`
- diagnostic schema: `4`

Removed from the active APK:

- 9:16 output and crop controls
- 30/60 FPS Shorts controls
- Shorts screen-capture encoder and service
- `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` permissions

The removed implementation remains preserved in v0.7.3 and earlier source snapshots.

## Desktop verification

The standard Windows/Android SDK 36 gate completed successfully:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --no-daemon
python .\tools\verify_release_sync.py
```

Results:

- 43 unit tests in 13 suites, 0 failures and 0 errors
- debug and release lint passed
- debug and release APK assembly passed
- root release source matches `versions/v0.7.4/` across 51 guarded files
- debug APK reports `local.djiunchained.voc.debug`, versionCode 11 and `0.7.4-debug`
- release APK reports `local.djiunchained.voc`, versionCode 11 and `0.7.4`
- neither APK declares an Android permission
- debug APK v2 signature verified
- release APK is intentionally unsigned
- active application source contains no Shorts, 9:16 or MediaProjection implementation reference

## Hardware verification

- DJI Goggles N3 USB connection and 1920×1080 live view passed on the target Android 16 phone.
- Controls, aspect selection, lossless original-stream MP4 and manual recovery passed.
- The captured MP4 decoded all 650 frames and reported zero recorder drops, timestamp corrections or recording-surface losses.
- Diagnostic schema 4 was exported and reviewed.
- Raw H.264 and auto reconnect passed subsequent user-confirmed checks; no second diagnostic export
  was retained for those follow-up checks.

See [docs/hardware-tests/v0.7.4.md](docs/hardware-tests/v0.7.4.md) for the evidence record.

The v0.7.4 prerelease was published on 2026-09-16.

The previous verification record remains in [BUILD-VERIFICATION-0.7.3.md](BUILD-VERIFICATION-0.7.3.md).
