# Release workflow

The GitHub repository is the canonical development tree.

For every numbered version:

1. Start a feature branch from the latest `main`.
2. Implement and test the change in the repository root.
3. Increment `versionCode` and `versionName` in `app/build.gradle`.
4. Copy the complete buildable source to `versions/vX.Y.Z/`, excluding caches, build output and APKs.
5. Update `README.md`, `VERSIONS.md`, `CHANGELOG.md`, `BUILD-VERIFICATION.md` and the matching release notes.
6. Run `.\tools\build-release.ps1 -ValidateOnly`.
7. Run `.\tools\build-release.ps1` to test, lint, assemble and create the guarded release package outside the repository.
8. Open and review a pull request. Merge only after CI succeeds.
9. Tag the merged `main` commit directly and create a GitHub prerelease. Do not create a separate snapshot-only tag commit.
10. Attach the generated debug APK, unsigned release APK, SHA-256 manifests, build information and source ZIP.
11. Start the next version only after fetching the updated `main`.

Never commit local diagnostics, raw H.264 captures, screen recordings, signing keys, Android SDKs, Gradle caches or generated build directories.
