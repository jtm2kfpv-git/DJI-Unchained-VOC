# Changelog

## 0.8.0-dev — Advanced recording foundation

Started:

- bounded, keyframe-aligned instant-replay buffer model
- keyframe-aware recording split policy
- v0.8 architecture, failure rules and hardware acceptance plan
- explicit development-version handling so CI permits active work while final releases still
  require an exact immutable source snapshot

Validation: 51 unit tests, debug/release lint and both APK assemblies pass. Replay writing, split
file rollover, interface controls and hardware acceptance remain pending.

## 0.7.4 — Focused live view and original recording

Removed:

- experimental 9:16 Shorts output, crop controls and 30/60 FPS selection
- Shorts MediaProjection foreground service, encoder and related Android permissions
- Shorts-specific diagnostic fields and tests

Changed:

- diagnostics advance to schema 4
- the interface now focuses on live view, aspect/display controls and original-stream recording
- the removed implementation remains available in v0.7.3 and earlier snapshots for a possible future redesign

Validation:

- 43 unit tests, debug/release lint, APK assembly and GitHub Actions pass
- hardware-tested with DJI Goggles N3 on the primary Android 16 phone
- validated 1920×1080 lossless MP4: 650 frames, 21.69 seconds, approximately 29.97 FPS and zero recorder drops
- raw H.264 and auto reconnect passed follow-up user checks after the recorded acceptance session

## 0.7.3 — Identity cleanup (unreleased)

Changed:

- replaced the legacy `local.n3view.voc` package and namespace with `local.djiunchained.voc`
- renamed the remaining current-source N3 View log and icon identifiers to DJI Unchained VOC equivalents
- preserved v0.7.2 and earlier source snapshots unchanged

Upgrade note: Android treats this application-ID migration as a different app. Remove the legacy package once before installing v0.7.3.

## 0.7.2 — Recording and layout iteration

Added:

- lossless original-stream MP4 remuxing with monotonic arrival timestamps
- automatic original recording to `Movies/DJI Unchained VOC`
- selectable raw H.264 fallback in Advanced settings
- 30/60 FPS Shorts selection and actual encoder-FPS diagnostics
- diagnostic schema 3 recorder timing and surface-loss fields
- top-aligned disconnected artwork with bottom-aligned controls

Removed or changed:

- removed the original-recorder document picker that caused preview-surface flicker
- removed the 50 FPS Shorts option
- original recording now requires valid video dimensions and a usable keyframe

Validation: desktop build, lint and 48 unit tests pass. New recording paths require device regression testing.

## 0.7.1 — Controls and diagnostics iteration

Added:

- responsive portrait/landscape bottom control tray
- larger touch targets and simplified primary actions
- compact USB/video/resolution/FPS status strip
- independent source aspect, presentation and output controls
- collapsible Advanced section
- diagnostic schema 2, bounded event timeline and expanded stream/device health metrics

Validation: hardware-tested on the target Android 16 device with DJI Goggles N3.

## 0.7.0 — Architecture checkpoint

Added the first implementation slice for the 29-change v0.7 program, including recording coordination, output profiles, 9:16 geometry, performance tracking and the surface-fed MP4 encoder foundation.

## 0.6.0 — DJI Unchained VOC preview

Added the branded UI, Logo v3, aspect selection, 9:16 viewport and experimental Shorts recording while preserving the proven USB/H.264 path.

Earlier history is preserved in each source snapshot and matching GitHub release.
