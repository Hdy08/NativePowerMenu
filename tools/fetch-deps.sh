#!/usr/bin/env bash
#
# Downloads the compile-time dependencies that are not vendored in this repository.
#
# Only the Xposed API stub jar is needed; it is "provided" at runtime by LSPosed, so nothing from
# it ends up in the APK.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="$ROOT_DIR/tools"
XPOSED_API_URL="${XPOSED_API_URL:-https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar}"
TARGET="$TOOLS_DIR/api-82.jar"

mkdir -p "$TOOLS_DIR"

if [ -f "$TARGET" ]; then
    echo "already present: $TARGET"
    exit 0
fi

echo "==> downloading Xposed API 82"
curl -fsSL -o "$TARGET" "$XPOSED_API_URL"
echo "==> saved $TARGET"
