# N3 Local View 0.2 build verification

Verified 26 August 2026.

## Build result

Command:

```text
gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --warning-mode all --stacktrace
```

Result: `BUILD SUCCESSFUL`

- Gradle 9.7.1
- Android Gradle Plugin 9.3.1
- compile/target SDK 36 (Android 16)
- minimum SDK 29
- Java source/target 17
- fourteen unit tests, zero failures, zero errors
- debug lint: no issues
- release lint: no issues
- R8-minified unsigned release APK produced
- v2-signed debug APK produced for bench testing

The review additionally verified randomized logiclink fragmentation across chunk sizes 1-31, byte-at-a-time Annex-B input, reconnect state reset, decoder-generation isolation during restarts, SPS/PPS change handling, fallback when a hardware codec rejects low-latency mode, all display sizing modes, and display-mode cycling.

`tools/verify_protocol.py` compared the two embedded N3 start/keepalive packets byte-for-byte with `samuelsadok/dji_protocol` commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce`. Both packets matched exactly, with captured lengths 55 and 37 bytes.

## Privacy and manifest inspection

`aapt2 dump permissions` and `apkanalyzer manifest permissions` returned an empty permission list for both APKs. In particular, the APK declares no Internet, network-state, location, camera, microphone, account, contact or storage permission.

The source scan also found no networking API, analytics, Firebase, Sentry, Bugsnag, OkHttp or Retrofit reference.

Declared optional feature only:

```text
android.hardware.usb.accessory
```

The debug APK is signed with the automatically generated Android debug certificate. Its certificate SHA-256 digest is:

```text
f5d92b8b332b61260cd23a530d946d2fc6a8793bec38a37a889d3e60b77b8514
```

The release APK is deliberately unsigned.

## Output hashes

```text
77764A8CBB9FE48B14E013D1D795B386E16EB34878E5103BC1918A56B409DE2A  N3-Local-View-0.2.0-debug.apk
543F2D1B257C964971FF1CC8F49C80FA471C33140E90506050D16382818E4B34  N3-Local-View-0.2.0-release-unsigned.apk
```

## Hardware-validation boundary

Version 0.1's USB enumeration, captured control packets, `logiclink` parser, H.264 assembly and hardware decoding have been confirmed on the user's Goggles N3 / O4 Pro or Avata 2 / Android 16 LineageOS setup. Version 0.2 deliberately leaves those protocol bytes and transport behavior intact. No Android device was attached to this build machine, so the new 0.2 display controls and live video still require an on-device regression test.

The debug package has the same application ID and debug certificate as 0.1, so this upgrades it in place:

```text
adb install -r "output/apk/N3-Local-View-0.2.0-debug.apk"
adb logcat -s N3LocalView
```

If a regression appears, the archived hardware-confirmed build can be restored (the `-d` flag permits the debug version-code downgrade):

```text
adb install -r -d "output/apk/archive/0.1.0-hardware-confirmed/N3-Local-View-0.1.0-hardware-confirmed-debug.apk"
```

Sign the release APK with a key controlled by the user only after the 0.2 hardware regression test succeeds.
