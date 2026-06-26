#!/usr/bin/env bash
#
# fps.sh — drive the AIGames FPS prototype on YOUR own Android emulator/phone via adb,
#          and collect screenshots / video / logcat for review.
#
# Why this exists: Claude Code runs in a headless cloud container with no KVM and no
# access to Google's emulator downloads, so it cannot run the game itself. You run this
# on your machine (where adb + an emulator or a USB phone live) and paste the resulting
# screenshots / logs back into the chat.
#
# Tap targets are derived straight from the source:
#   - HUD buttons (fire/move/switch/aim/jump/interact)  -> Hud.java (fixed pixel offsets)
#   - PLAY (hub) / CONTINUE (summary)                    -> FpsRenderer.java  (W/2, H - 84*us)
# The app forces IMMERSIVE FULLSCREEN LANDSCAPE, so screen pixels == the app's touch
# coordinates and these targets line up. (us = clamp(height/800, 1.4, 2.3).)
#
# Usage:  ./fps.sh <command> [args]      (run ./fps.sh help)
#
set -euo pipefail

PKG="com.aigames.fpsprototype"
ACT="$PKG/.MainActivity"
ADB="${ADB:-adb}"
OUT="${FPS_OUT:-./fps-out}"
SERIAL="${FPS_SERIAL:-}"        # optional: pin a device, e.g. FPS_SERIAL=emulator-5554

adbc(){ if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
die(){ echo "ERROR: $*" >&2; exit 1; }

need_device(){
  command -v "$ADB" >/dev/null 2>&1 || die "adb not found — install Android platform-tools and ensure 'adb' is on PATH."
  adbc get-state >/dev/null 2>&1 || die "no device — start an emulator or plug in a phone with USB debugging, then check: $ADB devices"
}

# Read the live resolution and compute landscape W>=H plus the UI scale us.
geom(){
  local s a b
  s=$(adbc shell wm size 2>/dev/null | sed -n 's/.*: \([0-9][0-9]*x[0-9][0-9]*\).*/\1/p' | tail -1)
  [ -n "$s" ] || die "could not read screen size from 'wm size'"
  a=${s%x*}; b=${s#*x}
  if [ "$a" -ge "$b" ]; then W=$a; H=$b; else W=$b; H=$a; fi   # landscape: W is the long edge
  US=$(awk -v h="$H" 'BEGIN{u=h/800.0; if(u<1.4)u=1.4; if(u>2.3)u=2.3; printf "%.4f", u}')
}

# Echo "X Y" screen pixels for a named tap target (geom must have run first).
target(){
  case "$1" in
    fire)                         awk -v W="$W" -v H="$H" 'BEGIN{printf "%d %d", W-255, H-255}';;
    move)                         awk -v H="$H"           'BEGIN{printf "%d %d", 240, H-240}';;
    switch)                       awk -v W="$W" -v H="$H" 'BEGIN{printf "%d %d", W-490, H-193}';;
    aim)                          awk -v W="$W" -v H="$H" 'BEGIN{printf "%d %d", W-110, 0.36*H}';;
    jump)                         awk -v W="$W" -v H="$H" 'BEGIN{printf "%d %d", W-110, 0.58*H}';;
    interact)                     awk -v W="$W" -v H="$H" 'BEGIN{printf "%d %d", 0.5*W, H-200}';;
    confirm|play|start|continue)  awk -v W="$W" -v H="$H" -v US="$US" 'BEGIN{printf "%d %d", 0.5*W, H-84*US}';;
    *) die "unknown tap target '$1' (fire|move|switch|aim|jump|interact|confirm)";;
  esac
}

cmd_size(){
  geom
  cat <<EOF
device (landscape): ${W} x ${H}    us=${US}
  confirm / PLAY / CONTINUE : $(target confirm)
  fire                      : $(target fire)
  move-stick centre         : $(target move)
  switch weapon             : $(target switch)
  aim toggle                : $(target aim)
  jump                      : $(target jump)
  interact (door)           : $(target interact)
EOF
}

cmd_doctor(){
  command -v "$ADB" >/dev/null 2>&1 || die "adb not found — install platform-tools."
  echo "adb: $($ADB version | head -1)"
  adbc devices
  adbc get-state >/dev/null 2>&1 || die "no device connected."
  if adbc shell pm list packages 2>/dev/null | grep -q "$PKG"; then
    echo "package $PKG : INSTALLED"
  else
    echo "package $PKG : NOT installed  (run: ./fps.sh install)"
  fi
  geom; echo "screen ${W}x${H}  us=${US}  (immersive landscape expected)"
}

cmd_install(){
  need_device
  local apk="${1:-}"
  if [ -z "$apk" ]; then
    apk=$(ls -t AIGamesFPSPrototype/Builds/Android/*.apk 2>/dev/null | head -1 || true)
    [ -z "$apk" ] && [ -f AIGamesFPS.apk ] && apk="AIGamesFPS.apk"
  fi
  [ -n "$apk" ] && [ -f "$apk" ] || die "APK not found — pass a path: ./fps.sh install path/to/AIGamesFPS.apk"
  echo "installing $apk ..."
  adbc install -r -t -g "$apk" || adbc install -r "$apk"
  echo "installed."
}

cmd_launch(){ need_device; adbc shell am start -n "$ACT" >/dev/null; echo "launched $ACT"; }
cmd_stop(){   need_device; adbc shell am force-stop "$PKG"; echo "stopped $PKG"; }

cmd_shot(){
  need_device; mkdir -p "$OUT"
  local n="${1:-shot}" f
  f="$OUT/${n}.png"
  adbc exec-out screencap -p > "$f"
  # tiny PNG => screencap likely failed; warn rather than hand back a broken file
  if [ ! -s "$f" ]; then die "screenshot was empty — try again, or update adb (exec-out needs adb >= 1.0.35)."; fi
  echo "saved $f"
}

cmd_shots(){
  need_device
  local n="${1:-8}" iv="${2:-2}" i
  for i in $(seq 1 "$n"); do cmd_shot "strip_$(printf '%02d' "$i")"; sleep "$iv"; done
}

cmd_record(){
  need_device; mkdir -p "$OUT"
  local secs="${1:-15}"
  echo "recording ${secs}s — play now..."
  adbc shell screenrecord --time-limit "$secs" --bit-rate 8000000 /sdcard/fps_rec.mp4
  adbc pull /sdcard/fps_rec.mp4 "$OUT/recording.mp4" >/dev/null
  adbc shell rm -f /sdcard/fps_rec.mp4
  echo "saved $OUT/recording.mp4"
}

cmd_log(){
  need_device; mkdir -p "$OUT"
  local secs="${1:-20}"
  adbc logcat -c 2>/dev/null || true
  echo "capturing logcat for ${secs}s — interact with the game now..."
  sleep "$secs"
  adbc logcat -d -v time > "$OUT/logcat_full.txt"
  adbc logcat -d -b crash -v time > "$OUT/logcat_crash.txt" 2>/dev/null || true
  grep -Ei "$PKG|AndroidRuntime|FATAL|libEGL|Adreno|Mali|OpenGLRenderer|glError|shader|GLSurface" \
       "$OUT/logcat_full.txt" > "$OUT/logcat_filtered.txt" || true
  echo "saved $OUT/logcat_full.txt  (+ logcat_filtered.txt, + logcat_crash.txt)"
}

cmd_monkey(){
  need_device
  local c="${1:-500}"
  echo "monkey stress test: $c events (crash-finder)"
  adbc shell monkey -p "$PKG" --pct-syskeys 0 --throttle 60 -v "$c"
}

cmd_tap(){ need_device; geom; adbc shell input tap $(target "${1:?usage: ./fps.sh tap <fire|move|switch|aim|jump|interact|confirm>}"); }

cmd_look(){
  need_device; geom
  local dir="${1:-right}" d="${2:-240}" cx cy
  cx=$(awk -v W="$W" 'BEGIN{printf "%d", 0.70*W}')
  cy=$(awk -v H="$H" 'BEGIN{printf "%d", 0.50*H}')
  case "$dir" in
    left)  adbc shell input swipe "$cx" "$cy" "$((cx-d))" "$cy" 200;;
    right) adbc shell input swipe "$cx" "$cy" "$((cx+d))" "$cy" 200;;
    up)    adbc shell input swipe "$cx" "$cy" "$cx" "$((cy-d))" 200;;
    down)  adbc shell input swipe "$cx" "$cy" "$cx" "$((cy+d))" 200;;
    *) die "look <left|right|up|down> [pixels]";;
  esac
}

cmd_move(){
  need_device; geom
  local dir="${1:-f}" d="${2:-150}" dur="${3:-600}" mx my
  mx=240; my=$(awk -v H="$H" 'BEGIN{printf "%d", H-240}')
  case "$dir" in
    f|fwd|forward) adbc shell input swipe "$mx" "$my" "$mx" "$((my-d))" "$dur";;
    b|back)        adbc shell input swipe "$mx" "$my" "$mx" "$((my+d))" "$dur";;
    l|left)        adbc shell input swipe "$mx" "$my" "$((mx-d))" "$my" "$dur";;
    r|right)       adbc shell input swipe "$mx" "$my" "$((mx+d))" "$my" "$dur";;
    *) die "move <f|b|l|r> [pixels] [ms]";;
  esac
}

# Scripted demo: start a run and exercise the world while screenshotting.
# NOTE: 'adb input' events are discrete & serialized — this is NOT skilled play
# (no true simultaneous move+look+fire), but it reliably surfaces the rendering:
# sky, houses, enemies, weapon view, HUD. K = number of action rounds.
cmd_play(){
  need_device; geom; mkdir -p "$OUT"
  local k="${1:-6}" i
  adbc logcat -c 2>/dev/null || true
  cmd_launch; sleep 3
  cmd_shot "play_00_hub"
  echo "starting a run (tap confirm)..."
  adbc shell input tap $(target confirm); sleep 2
  for i in $(seq 1 "$k"); do
    cmd_shot "play_$(printf '%02d' "$i")"
    case $((i % 4)) in
      1) cmd_look right 280;;
      2) cmd_look left 340;;
      3) cmd_look up 150;;
      0) cmd_look down 150;;
    esac
    adbc shell input tap $(target fire); adbc shell input tap $(target fire)
    cmd_move f 170 600
    [ $((i % 3)) -eq 0 ] && adbc shell input tap $(target aim)    || true
    [ $((i % 5)) -eq 0 ] && adbc shell input tap $(target switch) || true
    sleep 1
  done
  cmd_shot "play_$(printf '%02d' "$((k+1))")_final"
  adbc logcat -d -v time > "$OUT/play_logcat.txt"
  grep -Ei "$PKG|FATAL|AndroidRuntime|glError|shader|libEGL" "$OUT/play_logcat.txt" \
       > "$OUT/play_logcat_filtered.txt" || true
  echo "done — see $OUT/play_*.png and $OUT/play_logcat*.txt"
}

usage(){
  cat <<'EOF'
fps.sh — run & capture the AIGames FPS prototype on your own Android device via adb.

SETUP / INFO
  doctor                 check adb, device, install state, screen geometry
  install [apk]          install/replace the APK (default: newest build, else ./AIGamesFPS.apk)
  launch | stop          start / force-stop the game
  size                   print resolution + all computed tap coordinates

CAPTURE
  shot [name]            one screenshot -> fps-out/<name>.png
  shots [n] [interval]   n screenshots every <interval>s (a film strip)
  record [secs]          screen-record to fps-out/recording.mp4
  log [secs]             capture logcat (full + filtered + crash buffer)
  monkey [count]         random-event stress test (crash finder)

INPUT (during a run)
  tap <name>             name: fire|move|switch|aim|jump|interact|confirm
  fire | aim | jump | switch | interact | confirm
  look <left|right|up|down> [px]     turn the camera
  move <f|b|l|r> [px] [ms]           nudge the move stick

ALL-IN-ONE
  play [rounds]          launch, start a run, and exercise the world while
                         screenshotting (default 6 rounds) + dump logcat

Env: ADB=<path>  FPS_SERIAL=<device>  FPS_OUT=<dir>
Typical first run:
  ./fps.sh doctor && ./fps.sh install && ./fps.sh play 8
Then send me everything in ./fps-out/
EOF
}

main(){
  local cmd="${1:-help}"; shift || true
  case "$cmd" in
    doctor)   cmd_doctor "$@";;
    install)  cmd_install "$@";;
    launch)   cmd_launch "$@";;
    stop)     cmd_stop "$@";;
    size|coords) cmd_size "$@";;
    shot)     cmd_shot "$@";;
    shots)    cmd_shots "$@";;
    record)   cmd_record "$@";;
    log)      cmd_log "$@";;
    monkey)   cmd_monkey "$@";;
    tap)      cmd_tap "$@";;
    fire|aim|jump|switch|interact|confirm) need_device; geom; adbc shell input tap $(target "$cmd");;
    look)     cmd_look "$@";;
    move)     cmd_move "$@";;
    play)     cmd_play "$@";;
    help|-h|--help) usage;;
    *) echo "unknown command: $cmd" >&2; echo; usage; exit 1;;
  esac
}
main "$@"
