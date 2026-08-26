# DJI Unchained VOC 0.6 build verification

Verified 26 August 2026.

## Automated result

```text
gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Result: `BUILD SUCCESSFUL`

- compile/target SDK 36, minimum SDK 29, Java 17
- 29 unit tests, zero failures and zero errors
- debug lint passed with warnings treated as errors
- release lint passed with warnings treated as errors
- debug and R8-minified unsigned release APKs produced
- debug APK verifies with APK Signature Scheme v2
- embedded DJI control packets remain byte-exact: two packets, 55 and 37 bytes

## Privacy and package inspection

Declared permissions:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
```

These permissions support the user-initiated Shorts screen-capture service. Android displays a capture-consent dialog before each session and an ongoing notification while capture is active. The APK declares no Internet, location, camera, microphone, contacts, account, or broad-storage permission.

The source scan found no network client, analytics, advertising, Firebase, Sentry, Bugsnag, OkHttp, Retrofit, crash reporter, or updater.

## Output hashes

```text
7AD939EEEA782F149CCBD51FF269F00580D2F18944272FBBE77E1160D672107B  DJI-Unchained-VOC-0.6.0-debug.apk
7A6D9F4543FC8722DEA6D1795D31D8A7A53A7DA2B67D849B420618337A5D7446  DJI-Unchained-VOC-0.6.0-release-unsigned.apk
```

Debug signer certificate SHA-256:

```text
f5d92b8b332b61260cd23a530d946d2fc6a8793bec38a37a889d3e60b77b8514
```

## Hardware-validation boundary

No Android device or goggles were attached to the build machine. The proven v0.5 USB, protocol, decoder, watchdog, diagnostics, and raw-recorder path was preserved, but the following v0.6 behavior still requires an on-device regression test:

- Logo v3 launcher rendering and frame-aware disconnected background
- forced 16:9 and 4:3 display geometry on the actual goggles stream
- draggable 9:16 crop positioning
- MediaProjection consent and 720x1280 MP4 finalization on Android 16
- visual crop/letterbox behavior for the phone's exact physical screen ratio
- heat, performance, and frame pacing during Shorts capture

The Shorts recorder is therefore explicitly experimental. Raw H.264 remains the reliability and quality fallback.
