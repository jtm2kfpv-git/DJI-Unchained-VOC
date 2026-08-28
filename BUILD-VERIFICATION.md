# DJI Unchained VOC 0.7 development verification

Verified 26 August 2026 for the first v0.7 architecture slice.

## Automated result

```text
gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Result: `BUILD SUCCESSFUL`

- compile/target SDK 36, minimum SDK 29, Java 17
- 36 unit tests, zero failures and zero errors
- debug lint passed with warnings treated as errors
- debug APK built successfully
- merged debug package: `local.n3view.voc.debug`
- v0.6 remains preserved under its original folder and package identity

## Implemented in this first slice

- separate v0.7 application ID: `local.n3view.voc`
- output profiles for original-stream and 9:16 Shorts recording
- selectable 30, 50, and 60 fps model
- portrait-output and crop-range validation
- maximum duration and encoded-size limits
- single-recorder coordinator that prevents raw and MP4 recording overlap
- explicit control-lock and retryable-error state
- surface-fed hardware H.264/MP4 encoder boundary for the future GPU renderer
- selected-destination capacity check using the opened file descriptor
- truncating `rwt` output mode
- progress callbacks limited to twice per second
- safe muxer finalization and attempted deletion of incomplete output

## Privacy and migration boundary

The new `DirectMp4Encoder` uses no Internet or cloud service and has no MediaProjection dependency. The v0.6 MediaProjection activity/service path is still present temporarily while the GPU crop/render stage is implemented, so the development APK still declares:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
```

Those permissions are scheduled for removal before v0.7 is considered feature-complete. The app still declares no Internet, location, camera, microphone, contacts, account, or broad-storage permission.

## Output hash

```text
1447E3A16F072300B0963272163B089C05430FD30C243C92054FC45ACCFC3631  DJI-Unchained-VOC-0.7.0-development-debug.apk
```

## Hardware-validation boundary

This initial v0.7 APK is a development checkpoint, not a release candidate. Its new direct encoder has compiled and passed lint, but it is not yet connected to the decoded DJI frames because the GPU crop/render stage is the next milestone. The preserved v0.6 APK remains the current hardware-testable build.
