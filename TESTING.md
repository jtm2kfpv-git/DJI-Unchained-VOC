# DJI Unchained VOC — Hardware Test Checklist

Use this checklist for every Android/DJI test build. Mark each item `PASS`, `FAIL`, or `NOT TESTED`. Do not treat an untested item as passing.

## Test metadata

```text
App version:
APK filename:
Android version / ROM:
Phone model:
DJI goggles:
Air unit / aircraft:
Test date:
Tester:
Fresh install or upgrade:
```

## 1. Installation and identity

- [ ] APK installs without an ADB/package error.
- [ ] Launcher name is **DJI Unchained VOC**.
- [ ] Expected project icon is visible.
- [ ] App header reports the exact tested version.
- [ ] Android app information shows the expected debug or release package.
- [ ] The package is `local.djiunchained.voc.debug` for debug or `local.djiunchained.voc` for release.
- [ ] A device carrying the legacy `local.n3view.voc` package receives clear uninstall/reinstall instructions.
- [ ] App opens without an immediate crash.

## 2. Disconnected state

- [ ] Project artwork appears while no video input is detected.
- [ ] Artwork is aligned at the top as intended.
- [ ] Controls remain reachable at the bottom.
- [ ] Portrait layout is readable without clipped controls.
- [ ] Landscape layout is readable without clipped controls.
- [ ] Advanced settings expand and collapse correctly.

## 3. USB connection and live view

- [ ] Goggles are detected over a known-good USB data cable.
- [ ] Android USB permission flow appears when required.
- [ ] Connect establishes a session.
- [ ] Status changes to USB/video active.
- [ ] Live video appears without requiring DJI Fly.
- [ ] Resolution and FPS values become plausible and stable.
- [ ] Preview does not flicker, freeze, or repeatedly reconnect.
- [ ] Disconnect ends the session cleanly.
- [ ] Reconnect restores live video.

## 4. Display and aspect controls

- [ ] `FIT` preserves the image without unwanted cropping.
- [ ] `FILL` fills the viewport with expected cropping.
- [ ] `STRETCH` fills the viewport and visibly changes geometry as expected.
- [ ] `AUTO` uses the detected source aspect.
- [ ] `16:9` produces the expected geometry.
- [ ] `4:3` produces the expected geometry.
- [ ] Changing display/aspect modes does not interrupt decoding.
- [ ] The control tray can be hidden and restored as intended.

## 5. Original-stream lossless MP4

- [ ] Select `Output: Original stream` and lossless MP4.
- [ ] Recording starts without a document-picker interruption.
- [ ] Preview remains stable while recording.
- [ ] Recording status and duration update correctly.
- [ ] Stop finalizes the file without hanging or crashing.
- [ ] File appears in `Movies/DJI Unchained VOC`.
- [ ] File plays in a second player.
- [ ] Video duration is correct.
- [ ] Video has no obvious flicker, corruption, or frozen sections.
- [ ] A second recording can be started without restarting the app.

## 6. Original-stream raw H.264 fallback

- [ ] Enable raw H.264 in Advanced settings.
- [ ] Recording starts and preview remains stable.
- [ ] Stop closes the file cleanly.
- [ ] Raw file is non-empty.
- [ ] Raw file decodes in a compatible desktop/player tool.
- [ ] Switching back to lossless MP4 works without restarting the app.

## 7. Recovery and lifecycle

- [ ] Turning the goggles off produces a controlled disconnected state.
- [ ] Turning them back on allows manual recovery.
- [ ] Auto reconnect works when enabled.
- [ ] Backgrounding and reopening the app does not leave recording stuck.
- [ ] Screen rotation does not crash the app or lose the USB session unexpectedly.
- [ ] Keep-awake toggle behaves as labeled.

## 8. Diagnostics

- [ ] Export diagnostics after all recording modes have been exercised.
- [ ] Export identifies the correct app version and schema 4.
- [ ] USB, video, resolution, and FPS fields reflect the test.
- [ ] Parser resynchronizations and recovery actions are reviewed.
- [ ] Decoder and original-recorder metrics are present.
- [ ] Actual FPS, frame/access-unit counts, drops, and timestamp corrections are present where applicable.
- [ ] Export contains no video frames, precise location, account data, or unrelated personal information.

## Compact result report

Send this summary with the diagnostic export:

```text
Version:
Installation: PASS / FAIL / NOT TESTED
Disconnected layout: PASS / FAIL / NOT TESTED
USB live view: PASS / FAIL / NOT TESTED
Display/aspect modes: PASS / FAIL / NOT TESTED
Original lossless MP4: PASS / FAIL / NOT TESTED
Original raw H.264: PASS / FAIL / NOT TESTED
Reconnect/lifecycle: PASS / FAIL / NOT TESTED
Diagnostics export: ATTACHED / NOT ATTACHED

Failure reproduction steps:
Expected behavior:
Actual behavior:
Extra screenshot/video attached and why:
```

## Evidence discipline

- Attach one diagnostic export for the session instead of several near-identical exports.
- Attach a short recording only for visual/timing defects that diagnostics cannot show.
- Crop screenshots to the relevant UI when possible.
- Avoid capturing notifications or unrelated personal information.
- Preserve the failing output file until the defect has been diagnosed.
