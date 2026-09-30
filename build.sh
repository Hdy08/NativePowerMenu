#!/usr/bin/env bash
#
# Builds NativePowerMenu without Gradle.
#
# The module has no runtime dependency on the Android Gradle Plugin, so the whole build is just
# aapt2 -> javac -> d8 -> zipalign -> apksigner. Every tool path can be overridden through the
# environment; the defaults match the development container.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR="$ROOT_DIR/app"
BUILD_DIR="$ROOT_DIR/build"

# ---------------------------------------------------------------- toolchain configuration

# aapt2 is used to compile/link resources. Note: it must be able to parse the framework resource
# table it is given via -I, so we link against an Android 12 framework jar while compiling the Java
# sources against the newer SDK. The manifest only uses long-standing attributes.
AAPT2="${AAPT2:-aapt2}"
ANDROID_JAR_COMPILE="${ANDROID_JAR_COMPILE:-${ANDROID_HOME:-}/platforms/android-36/android.jar}"
ANDROID_JAR_LINK="${ANDROID_JAR_LINK:-${ANDROID_HOME:-}/platforms/android-32/android.jar}"
D8="${D8:-d8}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
APKSIGNER="${APKSIGNER:-apksigner}"
KEYTOOL="${KEYTOOL:-keytool}"
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
XPOSED_API_JAR="${XPOSED_API_JAR:-$ROOT_DIR/tools/api-82.jar}"

SDK_FALLBACK_DIR="${SDK_FALLBACK_DIR:-/root/build-tools}"
if [ ! -f "$ANDROID_JAR_COMPILE" ] && [ -f "$SDK_FALLBACK_DIR/android-36/android.jar" ]; then
    ANDROID_JAR_COMPILE="$SDK_FALLBACK_DIR/android-36/android.jar"
fi
if [ ! -f "$ANDROID_JAR_LINK" ] && [ -f "$SDK_FALLBACK_DIR/p32/android-12/android.jar" ]; then
    ANDROID_JAR_LINK="$SDK_FALLBACK_DIR/p32/android-12/android.jar"
fi
if ! command -v "$AAPT2" >/dev/null 2>&1 && [ -x /usr/bin/aapt2 ]; then
    AAPT2=/usr/bin/aapt2
fi
if ! command -v "$D8" >/dev/null 2>&1 && [ -x "$SDK_FALLBACK_DIR/android-16/d8" ]; then
    D8="$SDK_FALLBACK_DIR/android-16/d8"
fi

KEYSTORE="${KEYSTORE:-$ROOT_DIR/keystore/debug.keystore}"
KEYSTORE_PASS="${KEYSTORE_PASS:-android}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-android}"

MIN_SDK="${MIN_SDK:-31}"
JAVA_RELEASE="${JAVA_RELEASE:-11}"

# ---------------------------------------------------------------- helpers

die() {
    echo "error: $*" >&2
    exit 1
}

require_file() {
    [ -f "$1" ] || die "missing required file: $1${2:+ ($2)}"
}

echo "==> toolchain"
echo "    aapt2            : $AAPT2"
echo "    android.jar (javac): $ANDROID_JAR_COMPILE"
echo "    android.jar (link) : $ANDROID_JAR_LINK"
echo "    d8               : $D8"
echo "    xposed api       : $XPOSED_API_JAR"

require_file "$ANDROID_JAR_COMPILE" "set ANDROID_HOME or ANDROID_JAR_COMPILE"
require_file "$ANDROID_JAR_LINK" "set ANDROID_HOME or ANDROID_JAR_LINK"
require_file "$XPOSED_API_JAR" "run tools/fetch-deps.sh"
require_file "$APP_DIR/AndroidManifest.xml"

# ---------------------------------------------------------------- clean

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR"/{res,classes,dex,gen}

# ---------------------------------------------------------------- resources

echo "==> aapt2 compile"
"$AAPT2" compile --dir "$APP_DIR/res" -o "$BUILD_DIR/res.zip"

echo "==> aapt2 link"
"$AAPT2" link \
    -o "$BUILD_DIR/base.apk" \
    -I "$ANDROID_JAR_LINK" \
    --manifest "$APP_DIR/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version 36 \
    --java "$BUILD_DIR/gen" \
    --auto-add-overlay \
    "$BUILD_DIR/res.zip"

# ---------------------------------------------------------------- java

echo "==> javac"
find "$APP_DIR/src" "$BUILD_DIR/gen" -name '*.java' > "$BUILD_DIR/sources.txt"
"$JAVAC" \
    -encoding UTF-8 \
    -source "$JAVA_RELEASE" -target "$JAVA_RELEASE" \
    -Xlint:-options \
    -classpath "$ANDROID_JAR_COMPILE:$XPOSED_API_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

echo "==> d8"
find "$BUILD_DIR/classes" -name '*.class' > "$BUILD_DIR/classes.txt"
"$D8" \
    --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR_COMPILE" \
    --output "$BUILD_DIR/dex" \
    @"$BUILD_DIR/classes.txt"

# ---------------------------------------------------------------- package

echo "==> package"
OUT_APK="$BUILD_DIR/NativePowerMenu.apk"
cp "$BUILD_DIR/base.apk" "$OUT_APK"
cp "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/classes.dex"
(cd "$BUILD_DIR" && zip -q -X "$OUT_APK" classes.dex)
rm -f "$BUILD_DIR/classes.dex"

# assets/ is not added by aapt2 link (it expects them under the resource dir), so add it by hand.
if [ -d "$APP_DIR/assets" ]; then
    (cd "$APP_DIR" && zip -q -X -r "$OUT_APK" assets)
fi

# ---------------------------------------------------------------- sign

if [ ! -f "$KEYSTORE" ]; then
    echo "==> generating debug keystore"
    mkdir -p "$(dirname "$KEYSTORE")"
    "$KEYTOOL" -genkeypair -v \
        -keystore "$KEYSTORE" \
        -storepass "$KEYSTORE_PASS" \
        -alias "$KEY_ALIAS" \
        -keypass "$KEY_PASS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=NativePowerMenu, OU=Development, O=Local, L=Local, S=Local, C=CN" >/dev/null
fi

echo "==> zipalign"
"$ZIPALIGN" -f -p 4 "$OUT_APK" "$BUILD_DIR/NativePowerMenu-aligned.apk"

echo "==> apksigner"
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass "pass:$KEYSTORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    "$BUILD_DIR/NativePowerMenu-aligned.apk"

mv -f "$BUILD_DIR/NativePowerMenu-aligned.apk" "$OUT_APK"
"$APKSIGNER" verify --print-certs "$OUT_APK" | head -3

echo
echo "==> built: $OUT_APK"
ls -l "$OUT_APK"
