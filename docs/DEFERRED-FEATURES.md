# Deferred features

This document preserves ideas that were intentionally removed from the active APK. A deferred feature is not part of the current roadmap unless it is explicitly approved again.

## Vertical-video / former Shorts workflow

Status: **removed from v0.7.4; retained for possible redesign**

The v0.6.0–v0.7.3 line contained an experimental 9:16 viewport and 720×1280 MP4 recorder with selectable 30/60 FPS output. It used Android MediaProjection to record the displayed app viewport.

It was removed to keep the APK focused on low-overhead live view and original-stream recording, eliminate the screen-capture consent/service path, and remove its foreground-service permissions.

The last complete implementation is preserved in [`versions/v0.7.3/`](../versions/v0.7.3/), including:

- `CaptureProfile.java`
- `DirectMp4Encoder.java`
- `ShortsCaptureService.java`
- the former MainActivity output, FPS and drag-to-crop controls
- the manifest service/permissions and corresponding tests

A future version should not simply restore that code. Prefer a direct video pipeline that crops or reframes decoded frames without recording the Android screen or application controls. Reintroduction should require:

- measured performance on the target phone
- correct 9:16 geometry independent of device orientation
- stable monotonic MP4 timestamps
- no effect on USB reception or live preview
- explicit privacy and permission review
- new hardware tests at every supported output frame rate

## Other retained ideas

- Instant replay buffer with a configurable retained duration.
- Automatic splitting of long recordings into user-configurable parts.
