# Archive

[![Android Cloud Build](https://github.com/ikashef2/Muse/actions/workflows/android.yml/badge.svg)](https://github.com/ikashef2/Muse/actions/workflows/android.yml)

Archive is a local-first Android music curator for a deliberately clean personal library. The player is secondary: new audio is quarantined, inspected, and manually approved before it appears in the verified library.

## Prototype 0.2

- Scans Android `MediaStore.Audio` without copying or uploading audio.
- Reads embedded metadata and technical properties.
- Calculates a deterministic metadata-health score.
- Separates the review Inbox from the verified Library.
- Supports canonical metadata drafts, manual verification, and trash suggestions.
- Detects device-library changes without replacing user edits when the source file is unchanged.
- Schedules a conservative background rescan after the first authorized scan.

## Safety boundary

This build never rewrites or deletes an audio file. Metadata edits are canonical records in Room. Physical tag writing and Android's recoverable Trash flow belong to the next milestone, after format-by-format atomic-write tests are in place.

## Recommended: build in GitHub Actions

The repository includes `.github/workflows/android.yml`. GitHub builds and tests the app on a clean Linux runner, then publishes the debug APK as the `Archive-debug-apk` artifact. Android Studio is not required for this path.

Follow [docs/GITHUB_BUILD.md](docs/GITHUB_BUILD.md) for the one-time setup and APK download steps.

The cloud build is pinned to JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, and Kotlin 2.1.0.

## Optional: open in Android Studio

1. Open the repository root.
2. Use JDK 17.
3. Select **Gradle wrapper** as the Gradle distribution. The project pins Gradle 8.9.
4. Make sure Gradle **Offline work** is disabled for the first sync.
5. Sync dependencies.
6. Run on an Android 13+ device and grant Music and Audio access.
7. Tap **Scan this device**.

The project targets SDK 35 and supports Android 8+ (API 26). The build itself uses Java 17.

## If local Android Gradle Plugin resolution fails

Use the GitHub Actions build first. If it succeeds, the project and plugin versions are valid and the local machine is failing to reach Google's Maven repository.

On Windows, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\tools\verify-repositories.ps1
```

Then check:

1. Android Studio > Settings > Build, Execution, Deployment > Gradle > **Offline work** is off.
2. **Gradle JDK** is Android Studio's JBR 17 or another JDK 17.
3. `dl.google.com` is reachable through the active VPN/proxy.
4. Gradle is set to use the wrapper configuration rather than an arbitrary local version.

Do not add an untrusted Maven mirror merely to make the error disappear; build plugins execute code on the development machine.

## Next milestone: trustworthy mutation

1. Add exact fingerprint matching with Chromaprint/AcoustID and MusicBrainz.
2. Add duplicate clusters with exact hash + acoustic similarity.
3. Add an evidence screen comparing embedded, inferred, and remote metadata field by field.
4. Implement atomic tag writes for MP3, FLAC, M4A/AAC, OGG, and Opus.
5. Preserve pre-edit tags and validate the rewritten file before replacement.
6. Use `MediaStore.createWriteRequest()` for consent and `createTrashRequest()` for recoverable removal.
7. Export a portable JSON metadata snapshot and M3U playlists.

## Archive rules

- No unidentified track enters the verified library.
- Remote matches are suggestions, never silent truth.
- Album edition and release date are explicit; remasters never overwrite originals.
- Featured artists are modeled as credits, not embedded in a dirty title string.
- Persian display names, Latin sort names, and aliases will be separate fields.
- A destructive operation must be recoverable or explicitly confirmed.
