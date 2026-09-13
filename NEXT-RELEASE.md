# Next Release — v0.7.3

Status: **Implementation in progress — identity migration approved**

Baseline: **v0.7.2**

Release type: **Stabilization candidate**

This file is the single checklist for the next version. Changes should not be implemented merely because they appear under “Candidates.” Move an item into “Approved scope” only after we agree on it.

## Primary objective

Complete the missing v0.7.2 hardware regression, remove the obsolete N3 View internal identity, and produce a reliable v0.7.3 test build without destabilizing the working USB/video path.

## Approved scope

- Replace the legacy `local.n3view.voc` application ID and Java namespace with `local.djiunchained.voc`.
- Rename remaining current-source log and resource identifiers from the old N3 View branding to DJI Unchained VOC.
- Preserve v0.7.2 and earlier snapshots exactly as historical releases.
- Clearly document that Android treats v0.7.3 as a new app, requiring removal of the legacy package before installation.

## Required evidence before implementation

- Completed test report based on [TESTING.md](TESTING.md).
- One diagnostic export for the overall session.
- For each failure: exact reproduction steps and expected versus actual behavior.
- A short recording or screenshot only when it reveals information that diagnostics cannot.

## Candidate changes

| Candidate | Reason | Decision |
|---|---|---|
| Correct any confirmed original-MP4 recording defect | New v0.7.2 path is not hardware-tested | Pending test |
| Correct any confirmed raw-H.264 recording defect | Advanced fallback needs device verification | Pending test |
| Correct any confirmed 30/60 FPS Shorts defect | Both encoder paths need device verification | Pending test |
| Refine control placement or touch behavior | Field handling can be improved from actual use | Pending observations |
| Add diagnostic fields that explain a demonstrated failure | Evidence-driven diagnostics reduce later debugging time | Pending test |

## Future candidates — v0.8

These are recorded ideas, not approved v0.7.3 work.

| Potential feature | Initial intent | Status |
|---|---|---|
| Instant replay buffer | Retain a configurable recent video window that can be saved after an event | Potential v0.8 feature |
| Split recordings into custom parts | Divide long recordings automatically into user-configurable segments | Potential v0.8 feature |

## Explicitly out of scope unless separately approved

- Replacing the working DJI USB/protocol implementation.
- Internet, cloud, account, telemetry, or advertising functionality.
- Deleting or overwriting earlier source snapshots or releases.
- Large UI redesigns unrelated to observed handling problems.
- Adding features while an unresolved recording regression remains.

## Release acceptance gate

- [ ] Final v0.7.3 scope approved.
- [ ] Root version name/code updated once.
- [ ] All approved changes implemented.
- [ ] Unit tests added or updated for changed logic.
- [ ] Clean unit-test, lint, debug-build, and release-build gate passes.
- [ ] Root source matches `versions/v0.7.3`.
- [ ] Debug APK identity, permissions, and signature verified.
- [ ] Target-phone checklist completed.
- [ ] DJI Goggles N3 live view passes.
- [ ] Original MP4 and raw H.264 recordings pass.
- [ ] Shorts 30 FPS and 60 FPS recordings pass.
- [ ] Diagnostic export reviewed for new errors or drops.
- [ ] README, version history, changelog, and project state updated.
- [ ] APKs, source ZIP, and SHA-256 manifests prepared.
- [ ] Publication explicitly approved.

## Implementation record

Keep this section short during development.

| Item | Result |
|---|---|
| Approved scope | Permanent DJI Unchained VOC application/package identity migration |
| Branch | merged into `main` via pull request #7 |
| Build result | Desktop gate passed: 48 tests, lint and debug/release assembly |
| Hardware result | Not started |
| Publication status | Not authorized |
