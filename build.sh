#!/usr/bin/env bash
# Builds the Dex+ LSPosed module.
# Signing uses KEYSTORE, KEY_ALIAS, KEYSTORE_PASSWORD, and KEY_PASSWORD.
# Those values are not stored in this repository.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -z "${TMPDIR:-}" ] && [ -d /data/data/com.termux/files/usr/tmp ]; then
    TMPDIR="/data/data/com.termux/files/usr/tmp"
fi
BUILD_DIR="${BUILD_DIR:-${TMPDIR:-/tmp}/dexplus_build}"
ANDROID_JAR="${ANDROID_JAR:-/data/data/com.termux/files/usr/share/java/android.jar}"
FINAL_APK="${FINAL_APK:-$PROJECT_DIR/DexPlus.apk}"

if [ -n "${FRAMEWORK_RES:-}" ] && [ -f "$FRAMEWORK_RES" ]; then
    LINK_INPUT="$FRAMEWORK_RES"
elif [ -f /system/framework/framework-res.apk ]; then
    LINK_INPUT="/system/framework/framework-res.apk"
else
    LINK_INPUT="$ANDROID_JAR"
fi

if [ ! -f "$ANDROID_JAR" ]; then
    echo "android.jar not found. Set ANDROID_JAR." >&2
    exit 1
fi

if [ -z "${KEYSTORE:-}" ]; then
    KEYSTORE="$PROJECT_DIR/keystore.jks"
fi
if [ ! -f "$KEYSTORE" ]; then
    echo "Keystore not found. Set KEYSTORE to your signing keystore." >&2
    exit 1
fi
if [ -z "${KEYSTORE_PASSWORD:-}" ] || [ -z "${KEY_ALIAS:-}" ] || [ -z "${KEY_PASSWORD:-}" ]; then
    echo "Set KEYSTORE_PASSWORD, KEY_ALIAS, and KEY_PASSWORD." >&2
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

echo "[1/5] Compiling resources..."
aapt2 compile --dir "$PROJECT_DIR/res" -o "$BUILD_DIR/compiled_res/res.zip"
aapt2 link \
    -I "$LINK_INPUT" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    -A "$PROJECT_DIR/assets" \
    --java "$BUILD_DIR/gen" \
    -o "$BUILD_DIR/unsigned_unaligned.apk" \
    "$BUILD_DIR/compiled_res/res.zip"

echo "[2/5] Compiling Java..."
find "$PROJECT_DIR/src" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
javac \
    -source 11 -target 11 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

echo "[3/5] Dexing module classes..."
find "$BUILD_DIR/classes/com/udunus/dexplus" -name "*.class" > "$BUILD_DIR/dex_classes.txt"
if command -v d8 >/dev/null 2>&1; then
    d8 --lib "$ANDROID_JAR" --min-api 31 --output "$BUILD_DIR/dex" $(cat "$BUILD_DIR/dex_classes.txt")
else
    if [ -z "${R8_JAR:-}" ] && [ -f /data/data/com.termux/files/usr/share/java/r8.jar ]; then
        R8_JAR="/data/data/com.termux/files/usr/share/java/r8.jar"
    fi
    if [ -z "${R8_JAR:-}" ] || [ ! -f "$R8_JAR" ]; then
        echo "d8 is not on PATH and R8_JAR is not set." >&2
        exit 1
    fi
    java -cp "$R8_JAR" com.android.tools.r8.D8 \
        --lib "$ANDROID_JAR" \
        --min-api 31 \
        --output "$BUILD_DIR/dex" \
        $(cat "$BUILD_DIR/dex_classes.txt")
fi

echo "[4/5] Packaging..."
jar -uf "$BUILD_DIR/unsigned_unaligned.apk" -C "$BUILD_DIR/dex" classes.dex
zipalign -f -p 4 "$BUILD_DIR/unsigned_unaligned.apk" "$BUILD_DIR/unsigned_aligned.apk"

echo "[5/5] Signing..."
apksigner sign \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:${KEYSTORE_PASSWORD}" \
    --key-pass "pass:${KEY_PASSWORD}" \
    --out "$FINAL_APK" \
    "$BUILD_DIR/unsigned_aligned.apk"

echo "Build successful: $FINAL_APK"
