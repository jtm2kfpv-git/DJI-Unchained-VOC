# Next Release — v0.8.0

Status: **Stage 1 complete — replay integration is next**

Baseline: **v0.7.4 hardware-tested alpha prerelease**

Release theme: **Advanced lossless recording**

This file is the single delivery checklist for v0.8.0. Technical decisions are detailed in
[docs/V0.8-ARCHITECTURE.md](docs/V0.8-ARCHITECTURE.md).

## Primary objective

Add instant replay and configurable recording parts while preserving the privacy, low overhead and
proven USB/live-view behavior of v0.7.4.

## Approved features

### Instant replay

- Optional rolling buffer of original compressed H.264 access units.
- 15, 30 and 60-second duration choices, initially set to 30 seconds when enabled.
- 128 MiB hard memory ceiling and clear reporting of the actual retained duration.
- Keyframe-aligned, independently playable lossless MP4 save.
- Buffer continues receiving while a replay snapshot is written.

### Split recordings

- Splitting disabled by default.
- Quick duration choices plus a custom 1–60 minute value.
- Safe rollover at the first usable IDR keyframe after the requested duration.
- Independently playable MP4 and raw H.264 parts with stable part numbering.

### Diagnostics and controls

- Diagnostic schema 5 with replay memory, duration, eviction and save metrics.
- Split configuration, part count, rollover delay and per-part result metrics.
- Large field-usable replay/recording actions inside the existing bottom control tray.
- Preferences survive normal app restarts without automatically enabling memory use unexpectedly.

## Privacy and compatibility constraints

- No Internet, cloud, account, analytics, advertising or telemetry functionality.
- No new Android permissions, service or screen-capture path.
- No decoded-frame buffering or video re-encoding.
- No change to the working DJI USB subscription or LogicLink parser.
- Slow storage must never block USB reception or live decoding.
- v0.7.4 and every earlier source snapshot and release remain unchanged.

## Implementation checklist

### Stage 1 — core policies

- [x] Promote replay and split recording from deferred ideas into approved v0.8 scope.
- [x] Document memory, keyframe, file-boundary and failure rules.
- [x] Add a bounded, keyframe-aligned instant-replay buffer model.
- [x] Add a keyframe-aware recording split policy.
- [x] Mark the active build `0.8.0-dev` / versionCode 12 and keep release packaging locked.
- [x] Pass the complete desktop unit-test/lint/build gate: 51 tests and both APK variants.

### Stage 2 — replay integration

- [ ] Feed replay from the existing assembled access-unit path without blocking it.
- [ ] Add enable, duration and **Save replay** controls.
- [ ] Add asynchronous lossless replay-MP4 writing.
- [ ] Add replay status and diagnostic schema 5 fields.
- [ ] Add failure and lifecycle tests.

### Stage 3 — split integration

- [ ] Add split enable and duration controls.
- [ ] Move MediaStore destination creation behind a recorder part factory.
- [ ] Roll MP4 parts on an IDR boundary with fresh per-part timestamps.
- [ ] Roll raw H.264 parts with SPS/PPS/IDR beginnings.
- [ ] Add part-state interface and diagnostic fields.
- [ ] Add failure, stop, disconnect and boundary tests.

### Stage 4 — release preparation

- [ ] Remove the development qualifier and finalize version 0.8.0 / versionCode 12.
- [ ] Create and verify immutable `versions/v0.8.0` source snapshot.
- [ ] Pass unit tests, debug/release lint and both APK builds.
- [ ] Verify zero Android permissions and debug/release signing expectations.
- [ ] Complete replay and split hardware matrix on the primary phone and Goggles N3.
- [ ] Decode and inspect every supplied MP4 part and replay output.
- [ ] Update README, project state, version history and changelog.
- [ ] Build APK/source/checksum artifacts from merged `main`.
- [ ] Obtain explicit publication approval.

## Hardware acceptance highlights

1. Compare live-view stability and USB/parser diagnostics with replay off and on.
2. Save and decode 15, 30 and 60-second replay clips.
3. Confirm reported retained duration/memory match the saved output.
4. Produce at least three consecutive split parts and play each independently.
5. Stop and disconnect immediately before and after a rollover.
6. Exercise low-storage and writer-overload handling without losing live view.
7. Confirm auto reconnect safely closes active recording state and resumes video.

## Deferred beyond v0.8

- Reintroduction of vertical-video output.
- Audio capture or mixing.
- Background/headless recording.
- Raspberry Pi implementation; that remains a separate future project.
- Any network upload, streaming or cloud integration.
