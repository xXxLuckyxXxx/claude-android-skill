# `fps.sh` — run & test the game on *your* device, feed results back to Claude

Claude Code runs in a headless cloud container with **no KVM and no access to Google's
emulator downloads**, so it can't run the game itself. This toolkit lets *you* run the
APK on your own Android emulator or phone and hand the screenshots / logs back to Claude
for review.

All tap targets are derived directly from the source (`Hud.java` + `FpsRenderer.java`), and
the app runs **immersive-fullscreen landscape**, so `adb` pixel coordinates line up with the
game's touch zones automatically — no manual calibration needed in the normal case.

## Prerequisites

- **adb** (Android platform-tools) on your `PATH`. Check: `adb version`.
- A target, either:
  - **Emulator** — Android Studio ▸ Device Manager ▸ start any AVD (API 24+), or
  - **Phone** — enable *Developer options ▸ USB debugging*, plug in, accept the prompt.
- Confirm it's connected: `adb devices` (should list one device/emulator).

## Quick start

```bash
cd /path/to/this/repo
chmod +x tools/adb/fps.sh

tools/adb/fps.sh doctor        # checks adb, device, install state, resolution
tools/adb/fps.sh install       # installs the newest build (or pass a path)
tools/adb/fps.sh play 8        # starts a run and screenshots the action
# then send me everything in ./fps-out/
```

`install` auto-picks the newest APK from `AIGamesFPSPrototype/Builds/Android/`, falling back
to `./AIGamesFPS.apk`. Override: `fps.sh install path/to/AIGamesFPS.apk`.

### Windows (PowerShell — no Git Bash needed)

Use **`fps.ps1`** instead; same commands. Put `fps.ps1` + `AIGamesFPS.apk` in one folder, start
your AVD in Android Studio, then in PowerShell:

```powershell
cd C:\path\to\that\folder
# one-time, if the script is blocked:
Set-ExecutionPolicy -Scope Process Bypass
.\fps.ps1 doctor
.\fps.ps1 install
.\fps.ps1 play 8
```

It finds `adb` on `PATH`, or auto-falls back to `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
(set `$env:ADB` to override). Screenshots use device-side `screencap` + `adb pull` so PNGs stay
intact. Then send me everything in `.\fps-out\`.

## What to send back

After `play` (or your own session), zip/attach the **`fps-out/`** folder — it contains:

- `play_*.png` — a screenshot film-strip of the hub + gameplay
- `play_logcat*.txt` — runtime log (crashes, GL/shader errors)

For a specific thing I asked you to check, the handiest combos are:

```bash
tools/adb/fps.sh shots 10 1.5     # 10 screenshots, 1.5s apart, while you play
tools/adb/fps.sh record 20        # 20s screen recording -> fps-out/recording.mp4
tools/adb/fps.sh log 25           # capture logcat while you play for 25s
tools/adb/fps.sh monkey 800       # stress test: does it crash under random input?
```

## Manual control (for steering yourself while I watch the shots)

Run a single screenshot any time with `fps.sh shot <name>`. Drive input during a run:

```bash
tools/adb/fps.sh confirm                 # PLAY (from hub) / CONTINUE (from summary)
tools/adb/fps.sh look right 300          # turn camera; left|right|up|down
tools/adb/fps.sh move f 180 700          # nudge move stick; f|b|l|r [px] [ms]
tools/adb/fps.sh fire                     # also: aim, jump, switch, interact
tools/adb/fps.sh tap fire                 # generic form
```

See every computed coordinate for your screen: `tools/adb/fps.sh size`.

## Notes & limitations

- **Discrete input.** `adb input` events are serialized, so the `play` demo can't truly
  hold-move *and* look *and* fire at the same instant — it's a round-robin of nudges. Great
  for surfacing the **rendering** (sky, houses, enemies, weapon view, HUD); not a skill run.
- **If taps miss the buttons** (rare — only if your device reports tap coords in portrait
  while the app is landscape): run `fps.sh shot probe` during a run, open it, and tell me
  where the buttons actually are — or set `FPS_W` / `FPS_H` env overrides. I can adjust the
  offsets in `fps.sh`.
- **Software-GL emulators** (e.g. the default AVD GPU) render GLES2 slightly differently than
  a real Adreno/Mali phone GPU — fine for layout/feel, but for a true look, test on a phone.
- **Multiple devices:** pin one with `FPS_SERIAL=emulator-5554 tools/adb/fps.sh ...`.
