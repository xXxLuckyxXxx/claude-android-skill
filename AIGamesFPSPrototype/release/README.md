# Prebuilt APK download

A signed, installable build of the native companion app, committed here so it
can be downloaded straight from GitHub.

| | |
|---|---|
| File | `AIGamesFPS-v1.0.0-code5.apk` |
| Package | `com.aigames.fpsprototype` |
| Version | `1.0.0` (versionCode **5**) |
| Signing | project keystore, schemes v1 + v2/v3 (`apksigner verify` passes) |
| Min / Target SDK | 24 / 34 |

## Install
Download the `.apk`, copy it to an Android device, tap to install (allow
"install unknown apps" if prompted). Landscape; **left half = move**, **right
half = look**, **bottom-right button = fire**.

## Why a committed file and not a GitHub Release?
Formal GitHub Releases (the *Releases* tab with attached assets) can't be
created from the tooling used to generate this repo (read-only release APIs, no
`gh` CLI). The file is committed instead. To get an automated *Release* with the
APK attached, add a GitHub Actions workflow that runs `native-apk/build-apk.sh`
on a tag and uploads the result (the runner's `GITHUB_TOKEN` has the needed
`contents: write` permission) — see the note in `../README.md`.

> Build artifacts are normally git-ignored; this one is force-added on purpose.
