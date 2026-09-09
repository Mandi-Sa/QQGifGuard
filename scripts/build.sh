#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE_DIR="${ROOT_DIR}/module"
VARIANT="${1:-debug}"

case "${VARIANT}" in
  debug|Debug)
    TASK=":app:assembleDebug"
    APK_REL="app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release|Release)
    TASK=":app:assembleRelease"
    APK_REL="app/build/outputs/apk/release/app-release.apk"
    ;;
  *)
    echo "Usage: $0 [debug|release]" >&2
    exit 2
    ;;
esac

if [[ -z "${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}" ]]; then
  echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT must be set." >&2
  exit 1
fi

SDK_DIR="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

if [[ ! -f "${MODULE_DIR}/local.properties" ]]; then
  sdk_prop="${SDK_DIR//\\//}"
  printf 'sdk.dir=%s\n' "${sdk_prop}" > "${MODULE_DIR}/local.properties"
  echo "Wrote ${MODULE_DIR}/local.properties"
fi

if [[ ! -x "${MODULE_DIR}/gradlew" ]]; then
  chmod +x "${MODULE_DIR}/gradlew" || true
fi

echo "Building ${VARIANT} with JAVA_HOME=${JAVA_HOME:-"(system default)"}"
echo "Android SDK: ${SDK_DIR}"

(
  cd "${MODULE_DIR}"
  ./gradlew --no-daemon "${TASK}"
)

APK_PATH="${MODULE_DIR}/${APK_REL}"
if [[ ! -f "${APK_PATH}" ]]; then
  echo "ERROR: expected APK not found: ${APK_PATH}" >&2
  exit 1
fi

OUT_DIR="${ROOT_DIR}/dist"
mkdir -p "${OUT_DIR}"
OUT_APK="${OUT_DIR}/QQGifGuard-${VARIANT}.apk"
cp -f "${APK_PATH}" "${OUT_APK}"

echo
echo "BUILD SUCCESS"
echo "APK: ${OUT_APK}"
