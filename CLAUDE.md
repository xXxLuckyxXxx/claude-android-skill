# CLAUDE.md — AIGames FPS Prototype (handoff for local Claude Code)

You are continuing a long-running collaboration. This file is your map: project state,
architecture, how to build/test/ship, and the hard rules. Read it fully before acting.

> **Why you're now running locally:** so you can drive a real Android emulator with `adb` —
> install builds, take **real screenshots**, push levels, and visually verify the actual game
> instead of editor stand-ins. That `adb` loop (below) is your most important new capability.

---

## 1. What this is

A from-scratch **Android FPS prototype**, package **`com.aigames.fpsprototype`**, built as a
**hand-written native OpenGL ES 2.0 renderer** — **no game engine** (no Unity/Unreal/Godot). The
whole game is essentially one big Java file plus a tiny Android shell. The world is **baked meshes +
axis-aligned coloured boxes**. UI text is a bitmap glyph atlas. It ships as a single signed APK.

**Current state: Build/versionCode 75.** Feature set:
- **Town:** ~24 houses on a 10 m grid with real streets + sidewalks + setbacks; muted-realistic
  palette; building archetypes (cottage / townhouse / block / shop); pitched gable roofs; chimneys;
  per-floor windows with "inhabited" glow glass; foundations, trim bands, multiple storeys.
- **Street furniture:** lamps, benches, market stalls, well, barrels, crates, planters, woodpiles,
  trees — all placed realistically (furniture on sidewalks, market on reserved lots, nothing on the
  asphalt or clipping houses).
- **Walk-in furnished interiors:** beds, wardrobes, tables, chairs — a baked visual mesh plus
  collision proxies, so you can enter houses and bump into furniture.
- **Gameplay:** zombie/enemy waves + boss waves; multiple weapons incl. a sniper; abilities &
  weapon-upgrade shop; hub with tabbed navigation; XP/levels; iron-sights/ADS; kill particles;
  proportional Roboto UI font.
- **Tooling:** `.lvl` v4 level format + a 3D browser level editor; levels pushed to the device via
  `adb` with **no rebuild**.

---

## 2. Your new superpower: the `adb` loop

The emulator runs on **this machine** now. Verify and iterate against the *real* game:

```bash
adb devices                                   # confirm an emulator/device is attached
# Install / update (same signing key → installs straight over the old build):
adb install -r AIGamesFPS.apk
# If you ever switch signing keys you'll get INSTALL_FAILED_UPDATE_INCOMPATIBLE — then once:
adb uninstall com.aigames.fpsprototype && adb install AIGamesFPS.apk
# Launch:
adb shell am start -n com.aigames.fpsprototype/.MainActivity
# REAL screenshot → read it back with the Read tool (this is the whole point of moving local):
adb exec-out screencap -p > /tmp/shot.png      # then: Read /tmp/shot.png
# Drive the game (landscape; touch controls): tap menus / swipe to look / move:
adb shell input tap <x> <y>
adb shell input swipe <x1> <y1> <x2> <y2> <ms>
# Push a level with NO rebuild (see §6):
adb push level.lvl /sdcard/Android/data/com.aigames.fpsprototype/files/level.lvl
adb shell am force-stop com.aigames.fpsprototype   # then relaunch to load it
adb logcat -d -s AndroidRuntime:E *:F             # crash diagnosis
```

Starting the emulator (if it isn't running): Android Studio ▸ Device Manager, or
`emulator -list-avds` then `emulator -avd <name>`.

**Workflow:** build → `adb install -r` → launch → `screencap` → `Read` the PNG → judge → edit →
repeat. Real screenshots replace the offline editor renders described in §7 (keep those as a fallback
when no device is attached).

---

## 3. Build & sign

**One script does everything:** `AIGamesFPSPrototype/native-apk/build-apk.sh`
(`javac → dalvik-dx (dex) → aapt2 → zipalign → apksigner`). It **auto-increments** `versionCode` in
`native-apk/version.properties` on every run (seamless-update counter), and writes the signed APK to
`AIGamesFPSPrototype/Builds/Android/AIGamesFPS_native_v1.0.0_codeNN.apk`.

```bash
bash AIGamesFPSPrototype/native-apk/build-apk.sh
```

**Prerequisites on this machine:**
- A **JDK** (`javac`/`java`; 8+ — the script targets Java 8 bytecode so dalvik-dx accepts it; 21 is fine).
- **Android SDK build-tools on PATH** for `aapt2` (or `aapt`), `zipalign`, `apksigner`
  — e.g. add `$ANDROID_HOME/build-tools/<version>` to PATH. You already have the SDK (you run the emulator).
- `python3` (the script uses it to insert `classes.dex` into the APK zip).
- **Build jars are vendored** in `native-apk/.tools/` (`android.jar`, `dalvik-dx.jar`) — committed to
  the repo, so compile+dex need **no** SDK-platform download.
- **Keystore** at `AIGamesFPSPrototype/keystore/aigames-release.keystore` + `keystore.properties` —
  **NOT in git** (it's a secret). See §8 for how to obtain it. Without it the build stops at the sign step.

**Signing identity must not drift.** Every release so far is signed with a key whose certificate
SHA-256 is `258b1f2fc0fad2e743cacec91897ed2f855089d8cebf93beefe6b07644c02061`. Keeping that key means
new builds install straight over old ones. Verify any APK:
```bash
apksigner verify --print-certs AIGamesFPS.apk | grep SHA-256
```

---

## 4. Ship cycle (build → GitHub Release)

The release workflow (`.github/workflows/publish-apk.yml`) does **NOT build** — it publishes the
**committed root `AIGamesFPS.apk`** as the repo's `latest` Release whenever that file changes on
`main`. So shipping = build locally, copy to root, commit, get it onto `main`:

1. `bash AIGamesFPSPrototype/native-apk/build-apk.sh`
2. `cp AIGamesFPSPrototype/Builds/Android/AIGamesFPS_native_v1.0.0_code<NN>.apk AIGamesFPS.apk`
3. **Keystore guard:** make sure no `*.keystore`/`*.jks`/`keystore.properties` is staged (see §8).
4. Commit `FpsRenderer.java` (+ editor if changed) **and** `AIGamesFPS.apk` **and** `version.properties`.
5. Push to the dev branch, open a PR, **squash-merge to `main`** → `publish-apk.yml` recreates the
   `latest` Release with the new APK. (Historically the dev branch has diverged from `main` via
   squash-merges, so before pushing do `git fetch origin main` then `git merge -s ours origin/main`
   to avoid a phantom merge conflict on the PR.)
6. Deliver the APK to the user (e.g. `SendUserFile`) if they're waiting on it.

There is also a tag-triggered `Release APK` workflow that *does* build on the runner using
`AIGAMES_KEYSTORE_BASE64` + password/alias repo secrets — relevant only if you ship via CI.

---

## 5. Architecture — `FpsRenderer.java` (the one file that matters)

`AIGamesFPSPrototype/native-apk/src/com/aigames/fpsprototype/FpsRenderer.java` is the renderer **and**
the game. (Shell classes: `MainActivity`, `FpsGLSurfaceView`, `InputState`, `Hud` — rarely touched.)

**Vertex format:** `pos(3) + normal(3) + uv(2)` = 8 floats, **STRIDE 32 bytes**.
Helpers: `put(d,o, x,y,z, nx,ny,nz, u,v)` writes one vertex; `box6(d,o, cx,cy,cz, hx,hy,hz)` writes a
6-face box using **HALF extents**.

**The world is a list of boxes** (`boxes`), each `float[]`:
```
[ cx,cy,cz,  w,h,d,  r,g,b,  (yaw @ idx9, optional),  (render-skip flag @ idx10, optional) ]
```
- `cx,cy,cz` = centre; `w,h,d` = **full** sizes.  Render loop **skips** a box when
  `b.length>10 && b[10]!=0f` (these are collision-only proxies, e.g. furniture whose *visual* is in
  the baked interior mesh).

**Draw helpers:** `drawWorld(buf,count,mode,r,g,b)` and `drawWorldRange(buf,first,count,mode,r,g,b)`
for grouped per-colour draws.

**Baked meshes + grouped draws** (perf: a handful of draw calls for the whole town):
`roofMesh/roofGroups`, `windowMesh/winGroups`, `bandMesh/bandGroups`, `interiorMesh/interiorGroups` —
each *group* is `{firstVert, vertCount, r,g,b}`. Interiors also bind a wood texture (`woodTex`).

**Collision is height-banded** (`collide()`): a box is *walkable on top* if
`b[1]+b[4]*0.5 <= footY+0.05`, *overhead/ignored* if `b[1]-b[4]*0.5 >= footY+1.8`, otherwise it blocks
on its XZ footprint inflated by the player radius (~0.32). Enemy steering (`clearOfBuildings`) ignores
low furniture so zombies path around, not through, it.

**World generation:** `buildWorldInto(boxes, doors, houses)` lays out the procedural town (grid
`{-30..30}` @ 10 m, streets at `{-25,-15,-5,5,15,25}`, road half-width 1.8 m, sidewalk out to 2.9 m);
`addAccessories(...)` places street furniture; `furnishAll(boxes, houses)` →
`furnishHouse(...)` adds interiors. `buildWorldFromFile(...)` parses a `.lvl` and falls back to
`buildWorldInto` if absent.

When you change geometry, keep the box/group conventions intact, and re-run the verification harness
(§7) plus a real `adb` screenshot (§2).

---

## 6. Level system + editor

**`.lvl` v4** is a plain-text level file: `H`/`B`/`R`/`T`/`F` element lines + `SET` world-look lines.
The game loads it from `<app-external-files>/level.lvl` =
`/sdcard/Android/data/com.aigames.fpsprototype/files/level.lvl`, so you can **iterate level layout
without rebuilding** — just `adb push` it there and relaunch (see §2).

**Editor:** `tools/editor/level-editor.html` — a standalone browser editor with 2D + 3D views and full
per-house controls (glass colour, door width/height/colour, foundation height/colour, trim band,
storeys). It serialises/parses `.lvl` v4. Keep its `PALETTE`/defaults and the `houseWindows3D`
per-floor logic in sync with the renderer when you change the look.

---

## 7. Offline verification harness (fallback when no device, and for fast geometry checks)

In `scratchpad/` (recreate as needed):
- **`PlaceTest.java`** — instantiates `FpsRenderer` **without** a Context via
  `sun.misc.Unsafe.allocateInstance` (reflection; run with
  `--add-opens java.base/sun.misc=ALL-UNNAMED`), then calls `buildWorldInto` + `furnishAll`
  reflectively and asserts placement invariants (houses off the asphalt, lamps/stalls/benches/trees
  clear, interior colliders inside houses and not blocking doorways). Compile against
  `native-apk/.tools/android.jar` + the compiled app classes. This catches placement regressions
  cheaply because no GL/Android calls run on those code paths.
- **Headless render** — Playwright + Chromium loads `editor-test.html` (the editor with its CDN
  swapped for a vendored `three.min.js`, three@0.128.0) and screenshots geometry replicated in JS.
  Useful before a device is up; **prefer a real `adb` screenshot** now that you're local.

Pillow (Python) is available for top-down town-plan PNGs.

---

## 8. The keystore (secret — never in git)

`keystore/aigames-release.keystore` + `keystore/keystore.properties` are **git-ignored** and live only
outside the repo. A fresh clone won't have them, so a local **signed** build needs you to provide them
**once**. Two paths:

- **Preserve the signing identity (recommended):** restore the existing keystore (from the user's
  secure backup, or the `AIGAMES_KEYSTORE_BASE64` repo secret) to
  `AIGamesFPSPrototype/keystore/aigames-release.keystore`, and create `keystore.properties` from
  `keystore.properties.example` (storePassword / keyPassword / keyAlias). This keeps SHA-256
  `258b1f2f…02061` so every build keeps installing over the last with **no uninstall**.
- **Start fresh:** `bash AIGamesFPSPrototype/scripts/generate-keystore.sh` makes a new key (it refuses
  to overwrite an existing one). The certificate SHA **changes**, so existing installs must be
  uninstalled once before the next build will install. Back the new key up immediately — losing it
  permanently breaks seamless updates.

`keystore.properties.example` + `keystore/README.md` document the format and the golden rule.

---

## 9. Hard constraints (do not break these)

1. **Branch:** develop on **`claude/admiring-clarke-jy4gkm`**. Never push to another branch without
   explicit permission. Get changes onto `main` only via PR + squash-merge.
2. **GitHub scope:** the GitHub integration is scoped to **`xXxLuckyxXxx/claude-android-skill`** only.
3. **Never commit the keystore.** No `*.keystore` / `*.jks` / `keystore.properties` ever enters git.
   Check `git status` before every commit. (The `.gitignore` blocks them; don't `-f` past it.)
4. **Keep the signing SHA `258b1f2f…02061`** unless the user explicitly accepts a one-time reinstall.
5. **Keep the assistant's model identifier out of every committed artifact** — commit messages, PR
   titles/bodies, code comments, and docs. (It once tripped a commit guard inside a `Co-Authored-By`
   trailer and the commit was denied.) In chat, identify with your configured id if asked; never write
   it into a file that gets committed.
6. **Never use a game engine.** This stays a hand-written native GLES 2.0 renderer.

---

## 10. Repo map

```
/CLAUDE.md                      ← this file
/AIGamesFPS.apk                 ← the committed, signed APK that publish-apk.yml releases
/README.md, /SKILL.md           ← project/skill docs
/.github/workflows/             ← publish-apk.yml (release the committed APK) + Release APK (CI build)
/tools/editor/level-editor.html ← 3D level editor (.lvl v4)
/AIGamesFPSPrototype/
  native-apk/
    src/com/aigames/fpsprototype/
      FpsRenderer.java          ← THE game+renderer (edit here)
      MainActivity.java, FpsGLSurfaceView.java, InputState.java, Hud.java
    AndroidManifest.xml         ← package + MainActivity (landscape, GLES2 required)
    build-apk.sh                ← local build → signed APK (+ auto versionCode++)
    version.properties          ← versionCode (currently 75) / versionName 1.0.0
    .tools/                     ← VENDORED android.jar + dalvik-dx.jar (committed)
    stubs/                      ← JSR-239 GL stubs, compile-only (not dexed)
  keystore/                     ← signing material (gitignored secret) + README + .example
  scripts/generate-keystore.sh  ← create a fresh signing key (only if starting over)
  Builds/Android/               ← build-apk.sh output (gitignored)
```

Welcome aboard. Build → `adb install -r` → screenshot → `Read` → iterate.
