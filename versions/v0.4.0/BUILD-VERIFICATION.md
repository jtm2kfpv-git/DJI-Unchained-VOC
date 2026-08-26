# N3 Local View 0.4 build verification

Verified 26 August 2026.

## Build result

```text
gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --warning-mode all --stacktrace
```

Result: `BUILD SUCCESSFUL`

- Gradle 9.7.1 and Android Gradle Plugin 9.3.1
- compile/target SDK 36 (Android 16), minimum SDK 29
- Java source/target 17
- twenty-one unit tests, zero failures, zero errors
- debug and release lint: no issues
- v2-signed debug APK and R8-minified unsigned release APK produced

The watchdog tests verify the eight-second startup grace, keepalive/decoder/USB escalation timing, rendered-frame recovery cancellation, opt-in and display-surface gates, inactive-session behavior, and preservation of escalation when decoder counters reset.

## Protocol preservation

`tools/verify_protocol.py` compared the embedded start/keepalive data with `samuelsadok/dji_protocol` commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce`. Both packets still match byte-for-byte, with lengths 55 and 37 bytes.

The v0.4 watchdog resends only those existing packet copies. The logiclink parser, H.264 assembler and decoder input implementation are unchanged from v0.3. The new local diagnostics export contains no accessory serial number, account, location or network data.

## Privacy and APK inspection

`aapt2 dump permissions` returned zero permission declarations for both APKs. The source scan found no networking API, analytics, Firebase, Sentry, Bugsnag, OkHttp or Retrofit reference. Document export uses Android's Storage Access Framework and therefore requires no broad storage permission.

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
F43721D3C0D01F605989766F76E667281710AA5AB243B845E8CB70EABEC9F324  N3-Local-View-0.4.0-debug.apk
AB1F90C1EA92200361BC7706BEDD2E4E0F982ADFF339008CB3CD6CBE8F9D7B3A  N3-Local-View-0.4.0-release-unsigned.apk
```

## Hardware-validation boundary

The core stream path is hardware-confirmed through v0.1. No Android device was attached to this build machine, so v0.4's stream-stall timing and live recovery still require an on-device regression test. Automatic watchdog action is disabled unless the user enables **Auto reconnect**.

```text
adb install -r "output/apk/N3-Local-View-0.4.0-debug.apk"
adb logcat -s N3LocalView
```

All earlier source/APK releases remain under `GitHub-Submission/`.
