# Next Release — v0.7.3

Status: **Planning draft — scope not yet approved**

Baseline: **v0.7.2**

Release type: **Stabilization candidate**

This file is the single checklist for the next version. Changes should not be implemented merely because they appear under “Candidates.” Move an item into “Approved scope” only after we agree on it.

## Primary objective

Complete the missing v0.7.2 hardware regression, fix only demonstrated defects, and produce a reliable v0.7.3 test build without destabilizing the working USB/video path.

## Approved scope

No application changes are approved yet.

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

## Explicitly out of scope unless separately approved

- Replacing the working DJI USB/protocol implementation.
- Changing the application ID.
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
| Approved scope | Not set |
| Branch | `codex/development-docs` until v0.7.3 work begins |
| Build result | Not started |
| Hardware result | Not started |
| Publication status | Not authorized |
