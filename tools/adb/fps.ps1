<#
  fps.ps1 - drive the AIGames FPS prototype on YOUR Windows Android emulator/phone via adb.

  Native PowerShell port of fps.sh (no Git Bash needed). Works in Windows PowerShell 5.1
  and PowerShell 7+. Screenshots use device-side screencap + adb pull, which is binary-safe
  on Windows (PowerShell's '>' redirection would corrupt a piped PNG).

  Why: Claude Code runs headless with no KVM and no Google emulator downloads, so it can't
  run the game itself. You run this on your machine and paste the screenshots/logs back.

  Tap targets come straight from source (Hud.java + FpsRenderer.java). The app is immersive
  fullscreen landscape, so adb pixel coords line up with the game's touch zones.

  Usage:  .\fps.ps1 <command> [args]      (run  .\fps.ps1 help)
  If PowerShell blocks the script, run once:  Set-ExecutionPolicy -Scope Process Bypass
#>
[CmdletBinding()]
param(
  [Parameter(Position=0)][string]$Command = 'help',
  [Parameter(Position=1, ValueFromRemainingArguments=$true)][string[]]$Rest
)

$ErrorActionPreference = 'Stop'
# PowerShell 7.4+ turns a native non-zero exit into a throw under 'Stop'; disable that so our
# explicit $LASTEXITCODE checks (e.g. the install fallback) run. Harmless no-op on 5.1.
$PSNativeCommandUseErrorActionPreference = $false

$PKG = 'com.aigames.fpsprototype'
$ACT = "$PKG/.MainActivity"
$OUT = if ($env:FPS_OUT) { $env:FPS_OUT } else { './fps-out' }
$script:Serial = $env:FPS_SERIAL
$script:ADB = $null
$script:W = 0; $script:H = 0; $script:US = 1.4

function Die([string]$m){ Write-Host "ERROR: $m" -ForegroundColor Red; exit 1 }

function Resolve-Adb {
  if ($script:ADB) { return }
  if ($env:ADB -and (Test-Path $env:ADB)) { $script:ADB = $env:ADB; return }
  $c = Get-Command adb -ErrorAction SilentlyContinue
  if ($c) { $script:ADB = $c.Source; return }
  $cand = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
  if (Test-Path $cand) { $script:ADB = $cand; return }
  Die "adb not found. Install Android platform-tools (comes with Android Studio) and add it to PATH, or set `$env:ADB to adb.exe."
}

# Call adb (optionally pinned to a serial). $LASTEXITCODE reflects the real adb exit code.
function Adb {
  param([Parameter(ValueFromRemainingArguments=$true)]$a)
  if ($script:Serial) { & $script:ADB -s $script:Serial @a } else { & $script:ADB @a }
}

function Need-Device {
  Resolve-Adb
  & $script:ADB get-state *> $null
  if ($LASTEXITCODE -ne 0) { Die "no device. Start your AVD in Android Studio (Device Manager), then check: adb devices" }
}

# Read live resolution; store landscape W>=H and UI scale us = clamp(H/800, 1.4, 2.3).
function Get-Geom {
  Need-Device
  $raw = (Adb shell wm size) | Out-String
  $m = [regex]::Matches($raw, '(\d+)x(\d+)')
  if ($m.Count -eq 0) { Die "could not read screen size from 'wm size'." }
  $last = $m[$m.Count - 1]
  $a = [int]$last.Groups[1].Value; $b = [int]$last.Groups[2].Value
  if ($a -ge $b) { $script:W = $a; $script:H = $b } else { $script:W = $b; $script:H = $a }
  $u = $script:H / 800.0
  if ($u -lt 1.4) { $u = 1.4 }; if ($u -gt 2.3) { $u = 2.3 }
  $script:US = $u
}

# Return @(x, y) screen pixels for a named target (Get-Geom must have run).
function Get-Target([string]$n) {
  $W = $script:W; $H = $script:H; $US = $script:US
  switch ($n) {
    'fire'     { return @([int]($W - 255), [int]($H - 255)) }
    'move'     { return @(240,             [int]($H - 240)) }
    'switch'   { return @([int]($W - 490), [int]($H - 193)) }
    'aim'      { return @([int]($W - 110), [int](0.36 * $H)) }
    'jump'     { return @([int]($W - 110), [int](0.58 * $H)) }
    'interact' { return @([int](0.5 * $W), [int]($H - 200)) }
    'confirm'  { return @([int](0.5 * $W), [int]($H - 84 * $US)) }
    'play'     { return @([int](0.5 * $W), [int]($H - 84 * $US)) }
    'continue' { return @([int](0.5 * $W), [int]($H - 84 * $US)) }
    default    { Die "unknown tap target '$n' (fire|move|switch|aim|jump|interact|confirm)" }
  }
}

function Tap([string]$n) {
  Get-Geom
  $t = Get-Target $n
  Adb shell input tap $t[0] $t[1] | Out-Null
}

function Cmd-Size {
  Get-Geom
  Write-Host ("device (landscape): {0} x {1}   us={2}" -f $script:W, $script:H, [math]::Round($script:US,3))
  foreach ($n in 'confirm','fire','move','switch','aim','jump','interact') {
    $t = Get-Target $n
    Write-Host ("  {0,-22}: {1} {2}" -f $n, $t[0], $t[1])
  }
}

function Cmd-Doctor {
  Resolve-Adb
  Write-Host ("adb: " + ((& $script:ADB version) | Select-Object -First 1))
  & $script:ADB devices
  & $script:ADB get-state *> $null
  if ($LASTEXITCODE -ne 0) { Die "no device connected." }
  $pkgs = & $script:ADB shell pm list packages 2>$null
  if ($pkgs -match [regex]::Escape($PKG)) { Write-Host "package $PKG : INSTALLED" }
  else { Write-Host "package $PKG : NOT installed  (run: .\fps.ps1 install)" }
  Get-Geom
  Write-Host ("screen {0}x{1}  us={2}  (immersive landscape expected)" -f $script:W, $script:H, [math]::Round($script:US,3))
}

function Cmd-Install([string]$Apk) {
  Need-Device
  if (-not $Apk) {
    $cand = Get-ChildItem -Path 'AIGamesFPSPrototype/Builds/Android' -Filter *.apk -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($cand) { $Apk = $cand.FullName }
    elseif (Test-Path './AIGamesFPS.apk') { $Apk = './AIGamesFPS.apk' }
  }
  if (-not $Apk -or -not (Test-Path $Apk)) { Die "APK not found. Pass a path: .\fps.ps1 install C:\path\to\AIGamesFPS.apk" }
  Write-Host "installing $Apk ..."
  Adb install -r -t -g $Apk
  if ($LASTEXITCODE -ne 0) { Adb install -r $Apk | Out-Null }
  Write-Host "installed."
}

function Cmd-Launch { Need-Device; Adb shell am start -n $ACT | Out-Null; Write-Host "launched $ACT" }
function Cmd-Stop   { Need-Device; Adb shell am force-stop $PKG; Write-Host "stopped $PKG" }

function Cmd-Shot([string]$Name = 'shot') {
  Need-Device
  New-Item -ItemType Directory -Force -Path $OUT | Out-Null
  $dev = '/sdcard/__fps_shot.png'
  Adb shell screencap -p $dev | Out-Null
  $f = Join-Path $OUT "$Name.png"
  Adb pull $dev $f | Out-Null
  Adb shell rm -f $dev | Out-Null
  if (-not (Test-Path $f) -or (Get-Item $f).Length -lt 1000) { Die "screenshot empty/failed - try again." }
  Write-Host "saved $f"
}

function Cmd-Shots([int]$N = 8, [double]$Interval = 2) {
  $ms = [int]($Interval * 1000)   # honour fractional intervals (Start-Sleep -Seconds is int on PS 5.1)
  for ($i = 1; $i -le $N; $i++) {
    Cmd-Shot ("strip_{0:D2}" -f $i)
    Start-Sleep -Milliseconds $ms
  }
}

function Cmd-Record([int]$Secs = 15) {
  Need-Device
  New-Item -ItemType Directory -Force -Path $OUT | Out-Null
  Write-Host "recording $Secs s - play now..."
  Adb shell screenrecord --time-limit $Secs --bit-rate 8000000 /sdcard/__fps_rec.mp4
  $f = Join-Path $OUT 'recording.mp4'
  Adb pull /sdcard/__fps_rec.mp4 $f | Out-Null
  Adb shell rm -f /sdcard/__fps_rec.mp4 | Out-Null
  Write-Host "saved $f"
}

function Cmd-Log([int]$Secs = 20) {
  Need-Device
  New-Item -ItemType Directory -Force -Path $OUT | Out-Null
  Adb logcat -c 2>$null
  Write-Host "capturing logcat for $Secs s - interact with the game now..."
  Start-Sleep -Seconds $Secs
  $full = Join-Path $OUT 'logcat_full.txt'
  Adb logcat -d -v time | Out-File -FilePath $full -Encoding utf8
  $crash = Join-Path $OUT 'logcat_crash.txt'
  (Adb logcat -d -b crash -v time) 2>$null | Out-File -FilePath $crash -Encoding utf8
  $filt = Join-Path $OUT 'logcat_filtered.txt'
  Select-String -Path $full -Pattern $PKG,'AndroidRuntime','FATAL','libEGL','Adreno','Mali','OpenGLRenderer','glError','shader','GLSurface' |
    ForEach-Object { $_.Line } | Out-File -FilePath $filt -Encoding utf8
  Write-Host "saved $full  (+ logcat_filtered.txt, + logcat_crash.txt)"
}

function Cmd-Monkey([int]$Count = 500) {
  Need-Device
  Write-Host "monkey stress test: $Count events (crash finder)"
  Adb shell monkey -p $PKG --pct-syskeys 0 --throttle 60 -v $Count
}

function Cmd-Look([string]$Dir = 'right', [int]$D = 240) {
  Get-Geom
  $cx = [int](0.70 * $script:W); $cy = [int](0.50 * $script:H)
  switch ($Dir) {
    'left'  { Adb shell input swipe $cx $cy ($cx - $D) $cy 200 | Out-Null }
    'right' { Adb shell input swipe $cx $cy ($cx + $D) $cy 200 | Out-Null }
    'up'    { Adb shell input swipe $cx $cy $cx ($cy - $D) 200 | Out-Null }
    'down'  { Adb shell input swipe $cx $cy $cx ($cy + $D) 200 | Out-Null }
    default { Die "look <left|right|up|down> [pixels]" }
  }
}

function Cmd-Move([string]$Dir = 'f', [int]$D = 150, [int]$Dur = 600) {
  Get-Geom
  $mx = 240; $my = [int]($script:H - 240)
  switch ($Dir) {
    { $_ -in 'f','fwd','forward' } { Adb shell input swipe $mx $my $mx ($my - $D) $Dur | Out-Null }
    { $_ -in 'b','back' }          { Adb shell input swipe $mx $my $mx ($my + $D) $Dur | Out-Null }
    { $_ -in 'l','left' }          { Adb shell input swipe $mx $my ($mx - $D) $my $Dur | Out-Null }
    { $_ -in 'r','right' }         { Adb shell input swipe $mx $my ($mx + $D) $my $Dur | Out-Null }
    default { Die "move <f|b|l|r> [pixels] [ms]" }
  }
}

# Scripted demo: start a run and exercise the world while screenshotting.
# NOTE: adb input events are discrete/serialized - not skilled play (no simultaneous
# move+look+fire), but it reliably surfaces the rendering: sky, houses, enemies, gun, HUD.
function Cmd-Play([int]$K = 6) {
  Get-Geom
  New-Item -ItemType Directory -Force -Path $OUT | Out-Null
  Adb logcat -c 2>$null
  Cmd-Launch; Start-Sleep -Seconds 3
  Cmd-Shot 'play_00_hub'
  Write-Host "starting a run (tap confirm)..."
  $t = Get-Target 'confirm'; Adb shell input tap $t[0] $t[1] | Out-Null
  Start-Sleep -Seconds 2
  for ($i = 1; $i -le $K; $i++) {
    Cmd-Shot ("play_{0:D2}" -f $i)
    switch ($i % 4) {
      1 { Cmd-Look 'right' 280 }
      2 { Cmd-Look 'left'  340 }
      3 { Cmd-Look 'up'    150 }
      0 { Cmd-Look 'down'  150 }
    }
    $tf = Get-Target 'fire'
    Adb shell input tap $tf[0] $tf[1] | Out-Null
    Adb shell input tap $tf[0] $tf[1] | Out-Null
    Cmd-Move 'f' 170 600
    if ($i % 3 -eq 0) { $ta = Get-Target 'aim';    Adb shell input tap $ta[0] $ta[1] | Out-Null }
    if ($i % 5 -eq 0) { $ts = Get-Target 'switch'; Adb shell input tap $ts[0] $ts[1] | Out-Null }
    Start-Sleep -Seconds 1
  }
  Cmd-Shot ("play_{0:D2}_final" -f ($K + 1))
  $log = Join-Path $OUT 'play_logcat.txt'
  Adb logcat -d -v time | Out-File -FilePath $log -Encoding utf8
  $filt = Join-Path $OUT 'play_logcat_filtered.txt'
  Select-String -Path $log -Pattern $PKG,'FATAL','AndroidRuntime','glError','shader','libEGL' |
    ForEach-Object { $_.Line } | Out-File -FilePath $filt -Encoding utf8
  Write-Host "done - see $OUT\play_*.png and $OUT\play_logcat*.txt"
}

function Usage {
@'
fps.ps1 - run & capture the AIGames FPS prototype on your own Android device via adb.

SETUP / INFO
  doctor                 check adb, device, install state, screen geometry
  install [apk]          install/replace the APK (default: newest build, else .\AIGamesFPS.apk)
  launch | stop          start / force-stop the game
  size                   print resolution + all computed tap coordinates

CAPTURE
  shot [name]            one screenshot  -> fps-out\<name>.png
  shots [n] [interval]   n screenshots every <interval>s (a film strip)
  record [secs]          screen-record   -> fps-out\recording.mp4
  log [secs]             capture logcat (full + filtered + crash buffer)
  monkey [count]         random-event stress test (crash finder)

INPUT (during a run)
  tap <name>             name: fire|move|switch|aim|jump|interact|confirm
  fire | aim | jump | switch | interact | confirm
  look <left|right|up|down> [px]     turn the camera
  move <f|b|l|r> [px] [ms]           nudge the move stick

ALL-IN-ONE
  play [rounds]          launch, start a run, exercise the world while screenshotting (default 6)

Env: $env:ADB=<adb.exe>  $env:FPS_SERIAL=<device>  $env:FPS_OUT=<dir>

Typical first run (in the folder holding fps.ps1 + AIGamesFPS.apk, with your AVD running):
  .\fps.ps1 doctor
  .\fps.ps1 install
  .\fps.ps1 play 8
Then send me everything in .\fps-out\
'@ | Write-Host
}

switch ($Command) {
  'doctor'   { Cmd-Doctor }
  'install'  { Cmd-Install $Rest[0] }
  'launch'   { Cmd-Launch }
  'stop'     { Cmd-Stop }
  'size'     { Cmd-Size }
  'coords'   { Cmd-Size }
  'shot'     { Cmd-Shot ($(if ($Rest[0]) { $Rest[0] } else { 'shot' })) }
  'shots'    { Cmd-Shots ($(if ($Rest[0]) { [int]$Rest[0] } else { 8 })) ($(if ($Rest[1]) { [double]$Rest[1] } else { 2 })) }
  'record'   { Cmd-Record ($(if ($Rest[0]) { [int]$Rest[0] } else { 15 })) }
  'log'      { Cmd-Log ($(if ($Rest[0]) { [int]$Rest[0] } else { 20 })) }
  'monkey'   { Cmd-Monkey ($(if ($Rest[0]) { [int]$Rest[0] } else { 500 })) }
  'tap'      { Tap $Rest[0] }
  'fire'     { Tap 'fire' }
  'aim'      { Tap 'aim' }
  'jump'     { Tap 'jump' }
  'switch'   { Tap 'switch' }
  'interact' { Tap 'interact' }
  'confirm'  { Tap 'confirm' }
  'look'     { Cmd-Look ($(if ($Rest[0]) { $Rest[0] } else { 'right' })) ($(if ($Rest[1]) { [int]$Rest[1] } else { 240 })) }
  'move'     { Cmd-Move ($(if ($Rest[0]) { $Rest[0] } else { 'f' })) ($(if ($Rest[1]) { [int]$Rest[1] } else { 150 })) ($(if ($Rest[2]) { [int]$Rest[2] } else { 600 })) }
  'play'     { Cmd-Play ($(if ($Rest[0]) { [int]$Rest[0] } else { 6 })) }
  default    { Usage }
}
