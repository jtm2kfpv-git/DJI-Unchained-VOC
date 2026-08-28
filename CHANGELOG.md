# Changelog

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
