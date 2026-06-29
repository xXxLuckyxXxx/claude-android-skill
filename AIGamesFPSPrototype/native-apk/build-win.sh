#!/usr/bin/env bash
# Windows/Git-Bash adaptation of build-apk.sh:
#  - explicit Android build-tools paths (aapt2.exe / zipalign.exe run natively)
#  - apksigner invoked via `java -jar lib/apksigner.jar` (avoids the .bat wrapper)
#  - badging dump uses aapt2 (no aapt v1 on this box)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJ="$(cd "$HERE/.." && pwd)"
TOOLS="$HERE/.tools"
OUT="$HERE/build"

BT="${ANDROID_BT:-/c/Users/lukas/AppData/Local/Android/Sdk/build-tools/37.0.0}"
AAPT2="$BT/aapt2.exe"
ZIPALIGN="$BT/zipalign.exe"
APKSIGNER="$BT/lib/apksigner.jar"

ANDROID_JAR="$TOOLS/android.jar"
DX_JAR="$TOOLS/dalvik-dx.jar"
KEYDIR="$PROJ/keystore"
KS="$KEYDIR/aigames-release.keystore"
PROPS="$KEYDIR/keystore.properties"

prop() { grep -E "^$1=" "$2" | head -1 | cut -d= -f2- | tr -d '\r'; }

for f in "$ANDROID_JAR" "$DX_JAR" "$KS" "$AAPT2" "$ZIPALIGN" "$APKSIGNER"; do
  [ -f "$f" ] || { echo "MISSING: $f"; exit 1; }
done

STOREPASS="$(prop storePassword "$PROPS")"
KEYPASS="$(prop keyPassword "$PROPS")"
ALIAS="$(prop keyAlias "$PROPS")"

VP="$HERE/version.properties"
NAME="$(prop versionName "$VP")"
if [ -n "${AIGAMES_VERSION_CODE:-}" ]; then CODE="$AIGAMES_VERSION_CODE";
else CODE=$(( $(prop versionCode "$VP") + 1 )); sed -i "s/^versionCode=.*/versionCode=$CODE/" "$VP"; fi
echo "-> versionCode = $CODE   versionName = $NAME"

rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/stubs"

echo "-> javac (stubs)"
javac -nowarn -source 8 -target 8 -d "$OUT/stubs" $(find "$HERE/stubs" -name '*.java') 2>/dev/null

echo "-> javac (app)"
javac -nowarn -source 8 -target 8 -classpath "$ANDROID_JAR:$OUT/stubs" \
      -d "$OUT/classes" $(find "$HERE/src" -name '*.java') 2>/dev/null

echo "-> dex (dalvik-dx)"
java -cp "$DX_JAR" com.android.dx.command.Main --dex --output="$OUT/classes.dex" "$OUT/classes"

echo "-> aapt2 link"
APK_BASE="$OUT/base.apk"
"$AAPT2" link -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 24 --target-sdk-version 34 \
    --version-code "$CODE" --version-name "$NAME" -o "$APK_BASE"

echo "-> add classes.dex"
python3 - "$APK_BASE" "$OUT/classes.dex" <<'PY'
import sys, zipfile
apk, dex = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

echo "-> zipalign"
"$ZIPALIGN" -f 4 "$APK_BASE" "$OUT/aligned.apk"

mkdir -p "$PROJ/Builds/Android"
FINAL="$PROJ/Builds/Android/AIGamesFPS_native_v${NAME}_code${CODE}.apk"
echo "-> apksigner sign"
java -jar "$APKSIGNER" sign \
    --ks "$KS" --ks-pass "pass:$STOREPASS" --key-pass "pass:$KEYPASS" \
    --ks-key-alias "$ALIAS" --out "$FINAL" "$OUT/aligned.apk"

echo ""; echo "=== VERIFY ==="
java -jar "$APKSIGNER" verify --print-certs "$FINAL" | head -4
"$AAPT2" dump badging "$FINAL" 2>/dev/null | grep -E "^package:|launchable-activity:|sdkVersion:|targetSdkVersion:" || true
echo ""; echo "OK APK: $FINAL"; ls -la "$FINAL"
