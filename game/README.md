# Neon Racer 🏎️

A pretty **2.5D (pseudo-3D) OutRun-style racing game** for Android, rendered
entirely with the Canvas API — no external image assets, so the APK stays tiny
and the visuals stay crisp at any resolution.

## Features

- Pseudo-3D road with curves, hills, rumble strips and lane markers
- Animated neon-sunset sky with a retro sun and parallax hills
- Procedurally drawn glossy cars (player + traffic to dodge)
- Speed-based bounce, off-road slowdown and collision shake
- On-screen touch controls (steer left/right, accelerate, brake)
- Live speedometer, score and best-score HUD

## Controls

| Control | Action |
|---------|--------|
| ◀ / ▶ (bottom-left)  | Steer |
| ▲ (bottom-right)     | Accelerate |
| ⏸ (bottom-right)     | Brake |

Tap the screen to start.

## Building the APK

The CI workflow at `.github/workflows/build-apk.yml` builds a **signed release
APK** on every push and publishes it both as a workflow artifact
(`NeonRacer-APK`) and as a GitHub Release asset (`NeonRacer.apk`).

To build locally (requires the Android SDK):

```bash
cd game
./gradlew assembleRelease \
  -PRELEASE_STORE_FILE="$PWD/release.keystore" \
  -PRELEASE_STORE_PASSWORD=android \
  -PRELEASE_KEY_ALIAS=neonracer \
  -PRELEASE_KEY_PASSWORD=android
```

- `minSdk` 21, `targetSdk`/`compileSdk` 34
- Pure Java + `SurfaceView` render thread (no extra runtime dependencies)
