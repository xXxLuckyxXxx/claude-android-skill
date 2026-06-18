# AIGames FPS Prototype 🎯

Mobile-First-Person-Shooter-Prototyp für **High-End-Android** (Target: OnePlus 15
Pro-Klasse — Snapdragon 8 Elite 2, 165 Hz, **Vulkan**).

| | |
|---|---|
| **Engine** | Unity **6 LTS** (`6000.0.x`) + **Universal Render Pipeline (URP)** |
| **applicationId** | `com.aigames.fpsprototype` *(fix — Voraussetzung für nahtlose Updates)* |
| **Grafik-API** | Vulkan (only) |
| **Backend** | IL2CPP / ARM64 |
| **Texturen** | ASTC |
| **Eingabe** | Touch (virtueller Stick + Look-Drag + Feuer-Button) |

> **Engine-Wahl:** Unity URP, weil sich Projektdateien & Build-Pipeline
> vollständig text-/codebasiert (CLI-tauglich) aufsetzen lassen. Die komplette
> Build-Konfiguration ist **Config-as-Code** in
> `Assets/Editor/AIGamesBuild/` — keine fragilen, handgeschriebenen Asset-YAMLs.

---

## 🚀 Schnellstart

1. **Keystore erzeugen** (einmalig — *vor* dem ersten Build):
   ```bash
   ./scripts/generate-keystore.sh
   ```
   Legt `keystore/aigames-release.keystore` + `keystore/keystore.properties` an
   (beide **git-ignored**). ⚠️ **Sofort sichern** — siehe [keystore/README.md](keystore/README.md).

2. **Projekt in Unity 6 LTS öffnen** (mit installiertem *Android Build Support*
   inkl. SDK/NDK/JDK). Unity löst die Packages aus `Packages/manifest.json` auf.

3. **Menü `AI Games ▸ Initialize Project (All Steps)`** ausführen. Das:
   - setzt alle Player-Settings (Vulkan, IL2CPP/ARM64, ASTC, Linear, Landscape, Signing),
   - baut die High-End-URP-Pipeline (HDR, MSAA×4, Soft Shadows, **SSAO**, **Bloom**),
   - generiert die Test-Szene `Assets/_Project/Scenes/TestScene.unity`.

4. Szene öffnen und **Play** drücken (im Editor mit Maus testbar, am Gerät per Touch).

5. **APK bauen:** `AI Games ▸ Build ▸ 2. Build Android APK` → `Builds/Android/`.

---

## 🔁 Nahtlose APK-Updates (das Kernziel)

Android installiert eine neue APK **über** eine vorhandene (ohne Deinstallation),
wenn **alle drei** Bedingungen erfüllt sind — und genau die setzt dieses Projekt um:

| # | Bedingung | Umsetzung |
|---|-----------|-----------|
| 1 | **identische `applicationId`** | fix `com.aigames.fpsprototype` (`BuildScript.cs`) |
| 2 | **gleicher Signaturschlüssel** | derselbe Keystore für **jeden** Build (`generate-keystore.sh`, `BuildScript.ApplySigning`, `launcherTemplate.gradle`) |
| 3 | **streng steigender `versionCode`** | automatisch +1 pro Build (`VersionIncrementBuildProcessor.cs`) |

Debug- **und** Release-Builds werden mit **demselben** Schlüssel signiert — dadurch
lässt sich auch ein Release-Build über einen vorher installierten Debug-Build
aktualisieren (und umgekehrt).

> ⚠️ **Den Keystore niemals verlieren oder neu erzeugen.** Ein neuer Schlüssel
> bricht die Update-Kette: bestehende Installationen müssten deinstalliert werden.

---

## 🎨 Grafik-Targeting (High-End)

Konfiguriert in `GraphicsSetup.cs` (URP) + `BuildScript.cs` (Player):

- **Vulkan** als einzige Grafik-API (moderne Adreno/Mali-Treiber).
- **Linear Color Space** + **HDR** für korrektes PBR/Tonemapping.
- **MSAA ×4**, **Soft Shadows** (4 Cascades, 4096er Shadowmap), dynamische Punktlichter.
- **Screen Space Ambient Occlusion (SSAO)** als Renderer-Feature.
- Post-Processing-Volume: **Bloom**, **ACES-Tonemapping**, Color-Grading, Vignette.
- **ASTC**-Texturkompression (beste Qualität/Größe auf mobilen GPUs).
- Runtime: `Application.targetFrameRate = 165` (`GameBootstrap.cs`).

---

## 🕹️ Steuerung

- **Linker Stick** (unten links): Bewegung (Strafe/Vor-Zurück).
- **Rechte Bildschirmhälfte:** Wischen = Kamera (Yaw am Körper, Pitch geklemmt).
- **Feuer-Button** (unten rechts): Hitscan-Raycast mit Einschlagsmarker.

Die Touch-Widgets nutzen das `EventSystem` (Drag-Interfaces) — daher **keine**
Abhängigkeit zum neuen Input-System-Package nötig.

---

## 📁 Projektstruktur

```
AIGamesFPSPrototype/
├── Assets/
│   ├── _Project/
│   │   ├── Scripts/
│   │   │   ├── Player/    FPSController, PlayerShoot
│   │   │   ├── Controls/  VirtualJoystick, TouchLookArea
│   │   │   └── Core/      GameBootstrap
│   │   ├── Scenes/        TestScene.unity        (generiert)
│   │   ├── Settings/      URP-Assets             (generiert)
│   │   └── Art/Materials/ PBR-Platzhalter        (generiert)
│   ├── Editor/AIGamesBuild/
│   │   ├── BuildScript.cs                 ← Player-Settings + APK-Build (CI-Einstieg)
│   │   ├── VersionIncrementBuildProcessor.cs  ← versionCode++ pro Build
│   │   ├── KeystoreConfig.cs              ← liest env / keystore.properties
│   │   ├── GraphicsSetup.cs               ← High-End-URP
│   │   ├── TestSceneBuilder.cs            ← prozedurale Test-Szene
│   │   └── ProjectInitializer.cs          ← Ein-Klick-Setup
│   └── Plugins/Android/
│       └── launcherTemplate.gradle        ← optionales Gradle-Signing (debug+release)
├── ProjectSettings/ProjectSettings.asset  ← Identität (applicationId, Version)
├── Packages/manifest.json                 ← URP + Module
├── scripts/generate-keystore.sh           ← Keystore-Generator (idempotent)
├── keystore/                              ← Signaturmaterial (git-ignored)
└── ci/android-build.yml                   ← GitHub-Actions-Beispiel (GameCI)
```

---

## 🤖 CI (optional)

`ci/android-build.yml` ist ein einsatzfertiges **GameCI**-Beispiel. Zum
Aktivieren nach `.github/workflows/` verschieben und die Repo-Secrets setzen
(`UNITY_LICENSE`, `AIGAMES_KEYSTORE_BASE64`, `AIGAMES_KEYSTORE_PASSWORD`,
`AIGAMES_KEY_ALIAS`, `AIGAMES_KEY_PASSWORD`). Headless-Build-Aufruf:

```bash
Unity -batchmode -quit -nographics -projectPath . -buildTarget Android \
  -executeMethod AIGames.EditorTools.BuildScript.PerformAndroidBuild
```

---

## 📦 Native Begleit-APK (`native-apk/`)

Da in der Build-Umgebung **kein Unity-Editor** läuft und Googles Server dort
netzwerkseitig blockiert sind, liegt unter [`native-apk/`](native-apk/) eine
kleine **native GLES-2.0-FPS-App**, die sich mit erlaubten Quellen (apt + Maven
Central) zu einer **echten, signierten APK** bauen lässt. Sie teilt
`applicationId`, Keystore und die **Auto-`versionCode`-Logik** mit diesem
Projekt — demonstriert die Seamless-Update-Pipeline also real. Siehe
[`native-apk/README.md`](native-apk/README.md).

## ⚠️ Hinweise / Grenzen

- Dieses Repo wurde **ohne laufenden Unity-Editor** erstellt; die Logik ist
  idiomatisch und auf Robustheit ausgelegt (z. B. null-geschützte Setter), aber
  ein finaler Editor-Open/Build wurde nicht durchgeführt. Beim ersten Öffnen kann
  der Package-Manager URP-Versionen auflösen/aktualisieren.
- Die Unity-Version in `ProjectSettings/ProjectVersion.txt` ggf. an deine
  installierte Unity-6-LTS-Version anpassen.
- Szene, URP-Assets und Materialien werden **per Skript generiert** (nicht als
  Binär-/YAML-Assets eingecheckt), um GUID-/Versionskonflikte zu vermeiden.
```
