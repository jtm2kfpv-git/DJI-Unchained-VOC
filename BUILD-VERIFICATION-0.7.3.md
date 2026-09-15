# DJI Unchained VOC 0.7.3 build verification

Verification date: 2026-09-13

Status: **desktop verified; hardware test and publication pending**

## Scope

Version 0.7.3 removes the obsolete N3 View internal identity from the current application source while preserving every earlier source snapshot unchanged.

- application ID: `local.djiunchained.voc`
- debug application ID: `local.djiunchained.voc.debug`
- version code: `10`
- version names: `0.7.3` and `0.7.3-debug`
- application label: `DJI Unchained VOC`

Android treats the new application ID as a different app. The legacy package must be uninstalled once before installing v0.7.3.

## Desktop verification

The following gate completed successfully on Windows with Android SDK 36 and Java 17:

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --no-daemon
python .\tools\verify_release_sync.py
```

Results:

- 48 unit tests, 0 failures, 0 errors
- debug lint passed
- release lint passed
- debug APK assembled and debug-signed
- unsigned release APK assembled
- root release source matches `versions/v0.7.3/` across 56 guarded files
- debug and release APK metadata report the new application IDs and correct version

## Pending

- uninstall/migration instructions must be exercised on the target phone
- Goggles N3 live view and every v0.7.2 recording path require the existing hardware regression checklist
- v0.7.3 is not authorized for publication yet

The preserved v0.7.2 evidence remains in [BUILD-VERIFICATION-0.7.2.md](BUILD-VERIFICATION-0.7.2.md).
