## Problem

Describe the demonstrated problem or approved requirement.

## Change

Explain the implementation and why it is the smallest safe solution.

## Validation

- [ ] `testDebugUnitTest` passes
- [ ] `lintDebug` and `lintRelease` pass
- [ ] Debug and release APKs assemble
- [ ] `tools/verify_release_sync.py` passes when a version snapshot is included
- [ ] Relevant hardware checks in `TESTING.md` are complete, or the remaining device-test requirement is clearly stated
- [ ] Diagnostics, recordings and screenshots were reviewed for personal information
- [ ] No Internet, analytics, account, advertising or unnecessary Android permission was added

## Risk and rollback

Describe affected USB, decoder, recorder or UI paths and identify the last hardware-tested fallback.
