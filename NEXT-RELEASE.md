# Next Release — v0.7.4

Status: **Published as a hardware-tested alpha prerelease**

Baseline: **v0.7.3**

Release type: **Scope reduction and stabilization candidate**

This file is the single checklist for the next version. Candidate work is not approved merely because it appears below.

## Primary objective

Return the app to a focused, privacy-minimal USB live-view and original-stream recorder by removing the experimental Shorts workflow without changing the working DJI USB/H.264 path.

## Approved scope

- Remove the 9:16 Shorts output mode, crop interaction and 30/60 FPS selector.
- Remove the Shorts MediaProjection service, encoder, permission flow and foreground-service permissions.
- Remove Shorts-only diagnostic fields, strings, geometry and tests.
- Advance diagnostics to schema 4.
- Preserve v0.7.3 and all earlier source snapshots unchanged.
- Preserve the removed implementation in Git history and earlier snapshots for possible later redesign.

## Required hardware evidence

- Completed test report based on [TESTING.md](TESTING.md).
- One diagnostic export for the overall session.
- For each failure: exact reproduction steps and expected versus actual behavior.
- A short recording or screenshot only when diagnostics cannot demonstrate the problem.

## Candidate stabilization changes

| Candidate | Reason | Decision |
|---|---|---|
| Correct any confirmed original-MP4 recording defect | The remuxer still needs a complete device regression | Pending test |
| Correct any confirmed raw-H.264 recording defect | The advanced fallback needs device verification | Pending test |
| Refine control placement or touch behavior | Field handling can improve from actual use | Pending observations |
| Add diagnostics that explain a demonstrated failure | Evidence-driven fields reduce later debugging time | Pending test |

## Deferred features

These ideas are preserved for later evaluation and are not part of v0.7.4.

Implementation history and redesign criteria are recorded in [docs/DEFERRED-FEATURES.md](docs/DEFERRED-FEATURES.md).

| Potential feature | Initial intent | Status |
|---|---|---|
| Redesigned vertical-video export | Revisit 9:16 output without recording the Android screen or controls | Deferred; former Shorts implementation preserved through v0.7.3 |
| Instant replay buffer | Retain a configurable recent video window that can be saved after an event | Potential v0.8 feature |
| Split recordings into custom parts | Divide long recordings automatically into user-configurable segments | Potential v0.8 feature |

## Explicitly out of scope unless separately approved

- Replacing the working DJI USB/protocol implementation.
- Internet, cloud, account, telemetry or advertising functionality.
- Deleting or overwriting earlier source snapshots or releases.
- Reintroducing MediaProjection or the former Shorts screen-capture design.
- Large UI redesigns unrelated to observed handling problems.

## Release acceptance gate

- [x] Final v0.7.4 scope approved.
- [x] Root version updated to 0.7.4 / versionCode 11.
- [x] Shorts runtime, UI, permission and test code removed.
- [x] Clean unit-test, lint, debug-build and release-build gate passes.
- [x] Root source matches `versions/v0.7.4`.
- [x] Debug APK identity, permissions and signature verified.
- [x] Target-phone primary-workflow checklist completed.
- [x] DJI Goggles N3 live view passes.
- [x] Original lossless MP4 recording and playback pass.
- [ ] Raw H.264 fallback retest deferred; the path is unchanged from the earlier hardware-tested implementation.
- [x] Diagnostic schema 4 export reviewed for errors or drops.
- [x] README, version history, changelog and project state updated.
- [x] APKs, source ZIP, build metadata and SHA-256 manifests prepared and published.
- [x] Publication explicitly approved.

## Implementation record

| Item | Result |
|---|---|
| Approved scope | Remove the complete experimental Shorts/MediaProjection feature |
| Acceptance PR | [#10](https://github.com/jtm2kfpv-git/DJI-Unchained-VOC/pull/10), merged as `d075b77` |
| Build result | Passed: 43 tests, lint, debug/release assembly, APK identity/permission/signature inspection and snapshot parity |
| Hardware result | Primary workflow passed on POCO F2 Pro / Android 16 with DJI Goggles N3; raw H.264 and auto reconnect not retested |
| Publication status | [v0.7.4 hardware-tested alpha prerelease](https://github.com/jtm2kfpv-git/DJI-Unchained-VOC/releases/tag/v0.7.4) published with six verified assets |
