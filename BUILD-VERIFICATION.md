# DJI Unchained VOC 0.7.2 build verification

Verified 27 August 2026.

## Automated result

```powershell
.\gradlew.bat clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Result: `BUILD SUCCESSFUL`

- compile/target SDK 36, minimum SDK 29, Java 17
- 48 unit tests across 14 suites; zero failures and zero errors
- debug and release lint passed with warnings treated as errors
- debug and R8-minified unsigned release APKs produced
- debug APK verifies with APK Signature Scheme v2
- merged debug package: `local.n3view.voc.debug`
- version code 9, version name `0.7.2-debug`
- application label: `DJI Unchained VOC`
- byte-exact match for both embedded N3 control packets against `samuelsadok/dji_protocol` commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce`

## v0.7.2 verification scope

- source recording waits for valid dimensions, SPS, PPS and an IDR keyframe
- original H.264 is remuxed without re-encoding into the default MP4 container
- original recording uses monotonic arrival-time presentation timestamps
- optional raw H.264 begins with SPS/PPS even if the first IDR access unit omitted them
- original recording uses a bounded non-blocking queue and stops safely if storage cannot keep up
- completed original media is published from pending state; canceled, empty and failed output is deleted
- original recording no longer launches a document picker or intentionally pauses the video surface
- disconnected Logo v3 geometry is top-aligned; the responsive controls remain bottom-aligned
- Shorts capture supports only 30 and 60 fps
- the Shorts surface encoder requests the selected maximum rate, disables B-frames and repairs non-monotonic encoder timestamps
- original and Shorts diagnostic metrics include actual FPS, frame/access-unit count and timestamp corrections
- diagnostic schema is 3 and remains privacy-filtered
- old v0.7.0 and v0.7.1 source/APK folders remain unchanged

## Evidence from the supplied v0.7.1 test capture

- 1920×1080 streaming was stable at roughly 20 Mbit/s
- parser discarded/resynchronized bytes: 0
- recovery actions: 0
- raw-recorder dropped access units: 0
- all three supplied raw H.264 files decoded cleanly in FFmpeg
- the diagnostic lifecycle recorded activity pause and video-surface destruction exactly when the original recorder opened Android's document picker, identifying the preview-flicker cause addressed in v0.7.2
- the supplied Shorts file decoded, but its timestamps produced a non-monotonic DTS warning and approximately 59.8 actual fps while 50 fps was selected; v0.7.2 replaces that path and exposes only 30/60

## Privacy and permissions

Declared permissions:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
```

They are used only for the temporary Shorts screen-capture service. Original MP4/H.264 recording does not use MediaProjection. The APK declares no Internet, location, camera, microphone, contacts, account or broad-storage permission.

Diagnostic schema 3 stays local and excludes accessory/phone serials, account data, location, IP/MAC addresses, network history, document URIs and filesystem paths.

## Output hashes

```text
332609CED3DA7E42BB7B67FF486C78EAB1F486FE1A00D41108811CE4A3FC2B60  DJI-Unchained-VOC-0.7.2-debug.apk
1CE87B994B31B70E15B3A8D99DEFA0F803C0D9B726692F701A24123E83C4C495  DJI-Unchained-VOC-0.7.2-release-unsigned.apk
```

## Hardware-validation boundary

The supplied v0.7.1 diagnostics and recordings confirm its USB, decoder, preview and raw recorder on the target phone with DJI Goggles N3. v0.7.2 has been compiled, linted, unit-tested, inspected and packaged on the desktop, but—at the user's request—was not installed. The new lossless MP4 muxer and 30/60 Shorts encoder therefore remain pending a short on-device test.
