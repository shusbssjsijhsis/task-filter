#!/usr/bin/env bash
# Builds the module with the plain Android SDK tools (no Gradle).
#
# Required environment:
#   ANDROID_JAR     platform android.jar to compile against
#   BUILD_TOOLS     build-tools directory (aapt2, d8, zipalign, apksigner)
#   XPOSED_API_JAR  de.robv.android.xposed:api:82 (compile-only, never packaged)
#   KEYSTORE, KEYSTORE_PASS, KEY_ALIAS   signing key
# Optional: VERSION_CODE, VERSION_NAME
set -euo pipefail
cd "$(dirname "$0")"

out=build
rm -rf "$out"
mkdir -p "$out/classes" "$out/dex" "$out/apk"

mapfile -t sources < <(find src -name '*.java' | sort)
javac --release 8 -encoding UTF-8 -Xlint:-options \
    -cp "$ANDROID_JAR:$XPOSED_API_JAR" -d "$out/classes" "${sources[@]}"

mapfile -t classes < <(find "$out/classes" -name '*.class' | sort)
"$BUILD_TOOLS/d8" --release --min-api 26 --lib "$ANDROID_JAR" --classpath "$XPOSED_API_JAR" \
    --output "$out/dex" "${classes[@]}"

"$BUILD_TOOLS/aapt2" compile --dir res -o "$out/res.zip"
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code "${VERSION_CODE:-1}" --version-name "${VERSION_NAME:-0.1.0}" \
    -o "$out/apk/unsigned.apk" "$out/res.zip"
(cd "$out/dex" && zip -q -X ../apk/unsigned.apk classes.dex)

"$BUILD_TOOLS/zipalign" -f -p 4 "$out/apk/unsigned.apk" "$out/apk/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEYSTORE_PASS" \
    --out "$out/task-filter.apk" "$out/apk/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "$out/task-filter.apk" | grep -E 'SHA-256 digest'
echo "built $out/task-filter.apk"
