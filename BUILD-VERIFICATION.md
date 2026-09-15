# DJI Unchained VOC 0.7.4 build verification

Verification date: 2026-09-15

Status: **desktop verified; hardware test and publication pending**

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

## Pending

- Goggles N3 live view and original MP4/raw H.264 recording require a target-device regression
- diagnostic schema 4 requires an on-device export review
- v0.7.4 is not authorized for publication

The previous verification record remains in [BUILD-VERIFICATION-0.7.3.md](BUILD-VERIFICATION-0.7.3.md).
