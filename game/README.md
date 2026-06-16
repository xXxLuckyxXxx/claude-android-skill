# Turbo Circuit 🏎️ (Top-Down Racer)

A pretty **top-down (bird's-eye) racing game** for Android, rendered entirely
with the Canvas API — no external image assets, so the APK stays tiny and the
visuals stay crisp at any resolution.

## Features

- Smooth closed circuit built from a Catmull-Rom spline (asphalt, kerb border,
  animated dashed center line, checkered finish line)
- Arcade car physics: acceleration, braking, speed-dependent steering, coasting
  drag and an off-track grass penalty
- Camera that follows the car with a little speed-based look-ahead
- 3 AI rivals driving the racing line, with live position (P1/4)
- Lap counter, lap timer and best-lap tracking
- Glossy procedurally-drawn cars, scenery trees and a neon HUD
- On-screen touch controls

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
