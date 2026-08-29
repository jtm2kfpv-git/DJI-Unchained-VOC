# Contributing

Thanks for helping improve DJI Unchained VOC. This is an unofficial alpha project built around a hardware-tested DJI Goggles N3 USB/H.264 path, so changes should preserve the proven live-view behavior.

## Before opening a change

1. Read [`PROJECT-STATE.md`](PROJECT-STATE.md) and [`NEXT-RELEASE.md`](NEXT-RELEASE.md).
2. Discuss large features or protocol changes in an issue before implementation.
3. Do not include DJI proprietary binaries, signing keys, personal diagnostics, raw recordings or device identifiers.
4. Keep network access, analytics, advertising and account dependencies out of the app.

## Development workflow

1. Create a focused branch from current `main`.
2. Make the smallest change that solves the demonstrated problem.
3. Add or update unit tests where the behavior can be exercised without hardware.
4. Run:

   ```powershell
   .\tools\build-release.ps1 -ValidateOnly
   .\gradlew.bat testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
   ```

5. For hardware changes, complete the relevant sections of [`TESTING.md`](TESTING.md) and share only privacy-reviewed diagnostics.
6. Open a pull request explaining the problem, approach, validation and remaining hardware-test risk.

## Licensing

By submitting a contribution, you agree that it may be distributed under the Apache License 2.0. Do not submit artwork or other material you do not have permission to contribute. Branded artwork is governed separately by [`ASSET-LICENSE.md`](ASSET-LICENSE.md).
