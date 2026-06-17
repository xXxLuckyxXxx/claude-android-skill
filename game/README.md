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

### Campaign, shop & ghost

- **Driver level + XP + coins**: every race pays out coins and XP based on your
  finishing place; XP raises your driver level, which gates the shop
- **Garage shop** with 6 upgrades that each have a real **trade-off** (e.g. Turbo
  Engine: +top speed but less grip; Slick Tyres: +grip but bad in the rain).
  Buy with coins, then equip/unequip to tune a loadout per track
- **Ghost car**: your best lap on each track is recorded and replays as a
  translucent ghost so you can race your own line; saved across sessions
- Progress (coins, XP, upgrades, ghosts, unlocked levels) persists via prefs

### Levels, jumps & shortcuts

- **3 unlockable levels** (Sunset Bay → Neon Heights → Midnight Canyon), each a
  distinct Catmull-Rom circuit with its own AI difficulty, weather and time of day
- **Ramps/jumps** (blue chevrons) launch the car into the air with a landing boost
- **Shortcuts** (yellow-dashed side roads) let you cut the corner if you dare
- Level select + colour pick on the menu; finishing a level unlocks the next

### Architecture & playtesting

Gameplay lives in `Sim.java`, a **pure-Java simulation with no Android types**,
so it can be driven head-less. `tools/SimTest.java` runs an autopilot around
every level and asserts the race is completable, ramps fire and nothing NaNs:

```bash
cd game
javac -d /tmp/out app/src/main/java/com/fable/racer/Sim.java \
      app/src/main/java/com/fable/racer/Tracks.java tools/SimTest.java
java -cp /tmp/out com.fable.racer.SimTest
```

Still zero image assets — everything is drawn procedurally on the Canvas.

## Controls

| Control | Action |
|---------|--------|
| ◀ / ▶ (bottom-left)  | Steer |
| ▲ (bottom-right)     | Accelerate |
| ⏸ (bottom-right)     | Brake |

Tap the screen to start.

## Updating (no reinstall)

Every build is signed with the **same** committed key (`app/neonracer.jks`) and
gets an increasing `versionCode` (the CI run number), so a new APK installs
straight over the old one as an update — no uninstall, and your saved progress
(unlocked levels, best lap) is kept. The very first build that switched to this
fixed key needs one last uninstall; after that, updates are seamless.

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
