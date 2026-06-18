# native-apk/ — buildable, signed APK (companion build)

Unity cannot run in the build environment this prototype was scaffolded in
(no editor + no license), and Google's servers (Android SDK / Google Maven)
are network-blocked there. To still deliver a **real, installable, signed
APK** that demonstrates the pipeline, this folder contains a tiny **native
GLES 2.0 first-person app** built with a toolchain available from allowed
sources (Ubuntu apt + Maven Central).

It is intentionally minimal — it shares the Unity project's identity and the
**exact seamless-update mechanics**, so an APK built here installs as an update
over a previous one (and a later same-key build, Unity or native, updates over
this one).

| Aspect | Value |
|--------|-------|
| applicationId | `com.aigames.fpsprototype` (same as the Unity project) |
| Signing | `../keystore/aigames-release.keystore` (same key) |
| versionCode | auto-incremented by `build-apk.sh` on every run |
| Render | OpenGL ES 2.0: lit checkered floor + shaded box arena |
| Controls | left half = move stick, right half = look, landscape, immersive |

## What it shows on screen

A first-person view of a small arena (the same layout as the Unity test
scene: two pillars, two crates, a back wall and a cyan beacon) on a lit,
checkered floor. Drag the **left** half of the screen to move, the **right**
half to look around.

## Rebuild it

The compile toolchain is not committed. Recreate it once:

```bash
# 1) build tools (root): aapt/aapt2, apksigner, zipalign
sudo apt-get install -y aapt apksigner zipalign

# 2) compile target + dexer from Maven Central -> native-apk/.tools/
mkdir -p native-apk/.tools && cd native-apk/.tools
curl -L -o android.jar   https://repo1.maven.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar
curl -L -o dalvik-dx.jar https://repo1.maven.org/maven2/com/jakewharton/android/repackaged/dalvik-dx/16.0.1/dalvik-dx-16.0.1.jar
cd -

# 3) one-time keystore (if not already present)
./scripts/generate-keystore.sh

# 4) build a signed APK -> Builds/Android/AIGamesFPS_native_v<name>_code<code>.apk
./native-apk/build-apk.sh
```

## Seamless-update demo

Run `./native-apk/build-apk.sh` twice. The `versionCode` increments each time
(e.g. 2 → 3). Install the first APK, then install the second **without
uninstalling** — it upgrades in place because the applicationId and signing
key are identical and the versionCode is higher.

## Pipeline

`javac` (Java 8 bytecode) → `dalvik-dx` (DEX) → `aapt2 link` (manifest →
binary APK, with `--version-code` injected) → add `classes.dex` → `zipalign 4`
→ `apksigner` (v1 + v2/v3).

> The JSR-239 `GL10` / `EGLConfig` interfaces are missing from this android.jar,
> so `stubs/` provides compile-only versions. They are compiled to a separate
> classpath dir and deliberately **not** dexed — the device framework supplies
> the real classes at runtime.

## Build notes / caveats

- The APK is **structurally verified** here (`apksigner verify` passes, correct
  package/versionCode, aligned, only app classes dexed) but was **not** run on a
  physical device in this environment, so on-device rendering is unverified.
- This native app is a delivery vehicle for a working APK. The primary,
  full-featured target remains the **Unity URP project** in the parent folder.
