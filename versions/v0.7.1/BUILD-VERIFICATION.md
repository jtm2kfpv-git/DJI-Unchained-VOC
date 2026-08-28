# DJI Unchained VOC 0.7.1 build verification

Verified 26 August 2026.

## Automated result

```text
gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

Result: `BUILD SUCCESSFUL`

- compile/target SDK 36, minimum SDK 29, Java 17
- 41 unit tests, zero failures and zero errors
- debug and release lint passed with warnings treated as errors
- debug and R8-minified unsigned release APKs produced
- debug APK verifies with APK Signature Scheme v2
- merged debug package: `local.n3view.voc.debug`
- version code 8, version name `0.7.1-debug`

## v0.7.1 verification scope

- output-format selection is independent from source aspect selection
- capture-rate model cycles through 30, 50 and 60 fps
- raw and legacy Shorts recorders reject overlap
- aspect, output, FPS and display controls lock while recording
- legacy Shorts error state exposes a retry path
- rendered-frame UI work is limited to four updates per second
- control tray has scrollable portrait/landscape sizing and 48 dp minimum controls
- diagnostic event history is bounded at 200 entries and sanitizes line breaks
- rolling performance tracker covers 1/5/30-second FPS, bitrate extrema and frame gaps
- raw and legacy MP4 output use truncating `rwt` mode
- selected-destination storage is measured from the opened descriptor
- diagnostic report excludes document URIs and paths

## Privacy and permissions

Declared permissions:

```text
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION
```

These remain only for the temporary v0.6-derived Shorts screen-capture service. The new direct encoder has no MediaProjection dependency, but its GPU frame-input stage is not yet integrated. The APK declares no Internet, location, camera, microphone, contacts, account or broad-storage permission.

Diagnostic schema 2 stays local and excludes accessory/phone serials, account data, location, IP/MAC addresses, network history, document URIs and filesystem paths.

## Output hashes

```text
070A7332AE0078FED88904386354BFC539F43009D7B8920010403A88B6724486  DJI-Unchained-VOC-0.7.1-debug.apk
CD629A182320EA580D8394CF76CFEF2914FFF10829BC8A6713A8AC8386714DFC  DJI-Unchained-VOC-0.7.1-release-unsigned.apk
```

## Hardware-validation boundary

The v0.7.0 USB, decoder, display and raw-recorder checkpoint was hardware-confirmed on the POCO F2 Pro with DJI Goggles N3. The v0.7.1 control tray and expanded diagnostic export compile, test and lint successfully but still require a short on-device visual and hardware regression test.
