# Turbo Circuit 🏎️ (Top-Down Racer)

A pretty **top-down (bird's-eye) racing game** for Android, rendered entirely
with the Canvas API — no external image assets, so the APK stays tiny and the
visuals stay crisp at any resolution.

## Features

Researched the most-loved mechanics in the racing genre and packed in a lot:

1. **Drift physics** — handbrake/DRIFT button breaks lateral grip for slides
2. **Drift → Nitro** — drifting fills a nitro meter; **NITRO** button unleashes a boost
3. **Boost pads** on the track for instant speed
4. **Item boxes** (`?`) that grant a random weapon, Mario-Kart style
5. **Items**: TURBO (instant boost), OIL slick (spins out rivals), SHIELD (invulnerable)
6. **Bumping/combat** — shove AI cars; shield rams them harder
7. **Dynamic day/night cycle** with car headlight glows at night
8. **Weather** — random rain that darkens the scene and makes the track slippery
9. **Tyre smoke particles + persistent skid marks** while drifting
10. **Radar mini-map** showing the circuit and every car
11. **Race mode**: 3 laps, **3-2-1-GO countdown** and a **results screen** with standings
12. **Rubber-band AI** that keeps the race close
13. **Procedural audio** — synthesized engine note that tracks RPM, plus boost/pickup/skid SFX
14. **Persistent best lap** (saved across sessions) + car **colour selection**
15. Camera follow with look-ahead, screen shake, speed-lines and a neon HUD

Built on a smooth Catmull-Rom circuit (asphalt, kerb, animated centre line,
checkered finish) with glossy procedurally-drawn cars — still zero image assets.

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
