#!/usr/bin/env bash
# =============================================================================
#  build-apk.sh — build a SIGNED, installable APK without Gradle/Unity, using
#  only an Android-tools chain that is reachable from this environment:
#     javac (JDK) -> dalvik-dx (dex) -> aapt2/aapt (package) -> zipalign -> apksigner
#
#  Demonstrates the seamless-update pipeline end to end:
#     * fixed applicationId  : com.aigames.fpsprototype  (AndroidManifest.xml)
#     * same signing key      : ../keystore/aigames-release.keystore
#     * versionCode++         : auto-incremented here on every run
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
TOOLS="$HERE/.tools"
OUT="$HERE/build"

ANDROID_JAR="$TOOLS/android.jar"
DX_JAR="$TOOLS/dalvik-dx.jar"
KEYDIR="$PROJ/keystore"
KS="$KEYDIR/aigames-release.keystore"
PROPS="$KEYDIR/keystore.properties"

prop() { grep -E "^$1=" "$2" | head -1 | cut -d= -f2-; }

[ -f "$ANDROID_JAR" ] || { echo "✗ missing $ANDROID_JAR (see README: download tools)"; exit 1; }
[ -f "$DX_JAR" ]      || { echo "✗ missing $DX_JAR";       exit 1; }
[ -f "$KS" ]          || { echo "✗ missing keystore — run ../scripts/generate-keystore.sh"; exit 1; }

STOREPASS="$(prop storePassword "$PROPS")"
KEYPASS="$(prop keyPassword "$PROPS")"
ALIAS="$(prop keyAlias "$PROPS")"

# --- versionCode: shared monotonic env counter (authoritative) OR local -----
#     AIGAMES_VERSION_CODE is exported by CI (= github.run_number) and is shared
#     with the Unity builder, so both stay on ONE increasing sequence. Only the
#     local fallback writes back to version.properties.
VP="$HERE/version.properties"
NAME="$(prop versionName "$VP")"
if [ -n "${AIGAMES_VERSION_CODE:-}" ]; then
    CODE="$AIGAMES_VERSION_CODE"
    echo "→ versionCode = $CODE (from AIGAMES_VERSION_CODE)   versionName = $NAME"
else
    CODE=$(( $(prop versionCode "$VP") + 1 ))
    sed -i "s/^versionCode=.*/versionCode=$CODE/" "$VP"
    echo "→ versionCode = $CODE (local counter)   versionName = $NAME"
fi

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/stubs"

# --- compile JSR-239 stubs (compile-only classpath; deliberately NOT dexed,
#     the real GL10/EGLConfig come from the device framework at runtime) ------
echo "→ javac (compile-only stubs)"
javac -nowarn -source 8 -target 8 -d "$OUT/stubs" $(find "$HERE/stubs" -name '*.java')

# --- compile app (target Java 8 bytecode so dalvik-dx accepts it) -----------
echo "→ javac (app)"
javac -nowarn -source 8 -target 8 -classpath "$ANDROID_JAR:$OUT/stubs" \
      -d "$OUT/classes" $(find "$HERE/src" -name '*.java')

# --- dex ---------------------------------------------------------------------
echo "→ dex (dalvik-dx)"
java -cp "$DX_JAR" com.android.dx.command.Main --dex \
     --output="$OUT/classes.dex" "$OUT/classes"

# --- package manifest into a base APK ---------------------------------------
APK_BASE="$OUT/base.apk"
echo "→ aapt2 link"
if ! aapt2 link -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
        --min-sdk-version 21 --target-sdk-version 34 \
        --version-code "$CODE" --version-name "$NAME" \
        -o "$APK_BASE" 2> "$OUT/aapt2.log"; then
    echo "  aapt2 link failed, falling back to aapt v1:"; sed 's/^/    /' "$OUT/aapt2.log"
    sed "s#<manifest #<manifest android:versionCode=\"$CODE\" android:versionName=\"$NAME\" #" \
        "$HERE/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
    aapt package -f -M "$OUT/AndroidManifest.xml" -I "$ANDROID_JAR" -F "$APK_BASE"
fi

# --- add classes.dex into the APK -------------------------------------------
echo "→ add classes.dex"
python3 - "$APK_BASE" "$OUT/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

# --- align then sign (apksigner must run last) ------------------------------
echo "→ zipalign"
zipalign -f 4 "$APK_BASE" "$OUT/aligned.apk"

mkdir -p "$PROJ/Builds/Android"
FINAL="$PROJ/Builds/Android/AIGamesFPS_native_v${NAME}_code${CODE}.apk"
echo "→ apksigner sign"
apksigner sign \
    --ks "$KS" --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" \
    --ks-key-alias "$ALIAS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --min-sdk-version 21 \
    --out "$FINAL" "$OUT/aligned.apk"

echo ""; echo "=== VERIFY ==="
apksigner verify --print-certs "$FINAL" | head -6
echo "--- badging ---"
aapt dump badging "$FINAL" 2>/dev/null | grep -E "^package:|launchable-activity:|sdkVersion:|targetSdkVersion:|uses-feature.*[Oo]pen[Gg][Ll]" || true
echo ""; echo "✓ APK: $FINAL"; ls -la "$FINAL"
