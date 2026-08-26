# N3 Local View 0.3 build verification

Verified 26 August 2026.

## Build result

```text
gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --warning-mode all --stacktrace
```

Result: `BUILD SUCCESSFUL`

- Gradle 9.7.1
- Android Gradle Plugin 9.3.1
- compile/target SDK 36 (Android 16), minimum SDK 29
- Java source/target 17
- sixteen unit tests, zero failures, zero errors
- debug and release lint: no issues
- v2-signed debug APK and R8-minified unsigned release APK produced

The v0.3 tests include retry progression through 1/2/5/10 seconds, the 10-second cap and reset behavior, in addition to the existing display geometry, randomized logiclink fragmentation, byte-at-a-time Annex-B, reconnect state reset and packet-integrity coverage.

## Protocol preservation

`tools/verify_protocol.py` compared the two embedded N3 start/keepalive packets byte-for-byte with `samuelsadok/dji_protocol` commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce`. Both matched exactly, with lengths 55 and 37 bytes.

The v0.3 change is isolated to connection lifecycle/state handling and its UI. `N3Session`, the control packets, logiclink parser, H.264 assembler and decoder input path remain unchanged from v0.2.

## Privacy and APK inspection

`aapt2 dump permissions` returned zero permission declarations for both APKs. The source scan found no networking API, analytics, Firebase, Sentry, Bugsnag, OkHttp or Retrofit reference. The app still requests no Internet, network-state, location, camera, microphone, account, contact or storage permission.

Declared optional feature only:

```text
android.hardware.usb.accessory
```

The debug APK certificate SHA-256 digest remains:

```text
f5d92b8b332b61260cd23a530d946d2fc6a8793bec38a37a889d3e60b77b8514
```

## Output hashes

```text
8B9784D4641D80E601B198AE25BB723EAD9E737344BA2D0CD510F22B9C421C18  N3-Local-View-0.3.0-debug.apk
BE9CEBBAE90D8A5C006150E02A20CD84B26CD5936C951E4C2C78F6F593FA85B8  N3-Local-View-0.3.0-release-unsigned.apk
```

## Hardware-validation boundary

The core stream path is hardware-confirmed through v0.1. No Android device was attached to this build machine, so v0.3's retry behavior and live video require an on-device regression test.

Upgrade the existing debug installation in place:

```text
adb install -r "output/apk/N3-Local-View-0.3.0-debug.apk"
adb logcat -s N3LocalView
```

The debug application ID and signing certificate are unchanged. All previous source/APK releases remain under `GitHub-Submission/` for rollback and comparison.
