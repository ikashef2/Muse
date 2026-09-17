# Muse

[![Android Cloud Build](https://github.com/ikashef2/Muse/actions/workflows/android.yml/badge.svg)](https://github.com/ikashef2/Muse/actions/workflows/android.yml)

Muse is an intelligent personal music system for Android: a local-first library that cleans, understands, organizes, and expands your musical taste. The player is the surface — metadata quality, taste modeling, and discovery are the product.

## Current build: 0.5.0

- Scans Android `MediaStore.Audio` without uploading audio.
- Canonical metadata with Latin normalization and preserved original-script aliases (including Persian).
- Media3 playback with scoped queues, shuffle/repeat, play-next / add-to-queue, and session restore.
- Listening events recorded locally for taste and Home personalization.
- Home / Discover / Library / Search navigation with Import kept out of the primary listening path.
- Local Discover ranking with explainable taste-match reasons (no fake remote recommendations).
- Structured multi-label moods with manual override.
- Chromaprint / AcoustID / MusicBrainz / Apple catalog identification (AcoustID needs `ACOUSTID_CLIENT_KEY`).
- Atomic tag writes after explicit approval where the format is supported.

## Privacy

Listening history and taste signals stay on-device by default. External catalog calls send only the metadata needed for a match.

## Recommended: build in GitHub Actions

Follow [docs/GITHUB_BUILD.md](docs/GITHUB_BUILD.md). The cloud build is pinned to JDK 17, Gradle 8.9, Android Gradle Plugin 8.7.3, and Kotlin 2.1.0.

## Optional: open in Android Studio

1. Open the repository root.
2. Use JDK 17 and the Gradle wrapper.
3. Sync, run on Android 8+, grant Music and Audio access.
4. Tap **Import Music** / scan from Home or the Import inbox.

## Archive rules

- No unidentified track enters the verified library without review.
- Remote matches are suggestions, never silent truth.
- Featured artists should not live as dirty title strings.
- Persian display/original fields are retained while Latin canonical values drive matching and UI.
- A destructive operation must be recoverable or explicitly confirmed.

## Roadmap (next)

1. Paging for very large libraries (50k+).
2. Playlist intelligence and gradual suggested tracks.
3. Richer taste vectors and metal subgenre discovery.
4. Portable backup of playlists, corrections, and taste profile.
5. Recoverable MediaStore trash.
