# N3 Local View 0.5 build verification

Verified 26 August 2026.

## Build result

```text
gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease --warning-mode all --stacktrace
```

Result: `BUILD SUCCESSFUL`

- Gradle 9.7.1 and Android Gradle Plugin 9.3.1
- compile/target SDK 36 (Android 16), minimum SDK 29
- Java source/target 17
- twenty-six unit tests, zero failures, zero errors
- debug and release lint: no issues
- v2-signed debug APK and R8-minified unsigned release APK produced

Recorder tests verify waiting for an IDR keyframe, prepending missing SPS/PPS, avoiding duplicate parameters, stopping before a keyframe, handling a forced output failure, and terminating safely when a deliberately blocked writer fills the bounded queue.

## Recorder isolation

The recorder consumes immutable completed H.264 access units after they are submitted to the decoder. A dedicated single writer and a 120-access-unit bounded queue keep document-provider I/O off the USB/video thread. Queue overload or an `IOException` moves only the recorder to its error state; neither failure is thrown into the decoder or transport.

The output is raw Annex-B `.h264`, copied without re-encoding. Recording starts only when SPS, PPS and an IDR keyframe are available. On a USB reset the file is closed safely rather than joining discontinuous sessions.

## Protocol preservation

`tools/verify_protocol.py` compared the embedded start/keepalive data with `samuelsadok/dji_protocol` commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce`. Both packets remain byte-exact, with lengths 55 and 37 bytes. The logiclink parser and captured packet constants are unchanged from v0.4.

## Privacy and APK inspection

`aapt2 dump permissions` returned zero permission declarations for both APKs. Recording and diagnostics use Android's user-selected document picker, requiring no broad storage permission. The source scan found no networking API, analytics, Firebase, Sentry, Bugsnag, OkHttp or Retrofit reference.

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
6CBCD4932EC7AC59BA28A4ABF7D94FC58E7D047C3279477E95F1A2F789A40473  N3-Local-View-0.5.0-debug.apk
25308265EB4DF31C488A314BCA55D5CA50279461598807768F74D5FA29FB7073  N3-Local-View-0.5.0-release-unsigned.apk
```

## Hardware-validation boundary

The core stream path is hardware-confirmed through v0.1. No Android device was attached to this build machine, so the raw recording must still be checked with an actual N3 stream and VLC/FFmpeg.

```text
adb install -r "output/apk/N3-Local-View-0.5.0-debug.apk"
adb logcat -s N3LocalView
```

All earlier source/APK releases remain under `GitHub-Submission/`.
