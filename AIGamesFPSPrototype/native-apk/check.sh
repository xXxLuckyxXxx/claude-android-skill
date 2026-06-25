#!/usr/bin/env bash
# Quick compile-check (no APK packaging). Mirrors build-apk.sh's javac steps.
cd "$(dirname "$0")"
unset JAVA_TOOL_OPTIONS
rm -rf build_check && mkdir -p build_check/stubs build_check/classes
javac -nowarn -source 8 -target 8 -d build_check/stubs $(find stubs -name '*.java') 2>/dev/null
javac -nowarn -Xlint:none -source 8 -target 8 -classpath ".tools/android.jar:build_check/stubs" \
      -d build_check/classes $(find src -name '*.java')
rc=$?
if [ $rc -eq 0 ]; then echo "COMPILE OK"; else echo "COMPILE FAILED ($rc)"; fi
exit $rc
