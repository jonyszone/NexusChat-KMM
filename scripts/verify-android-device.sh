#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
adb="${ADB:-$sdk/platform-tools/adb}"
if [[ ! -x "$adb" ]]; then
    printf 'ADB not found: %s\n' "$adb" >&2
    exit 1
fi
if [[ -z "${ANDROID_SERIAL:-}" ]]; then
    printf 'Set ANDROID_SERIAL to one device from adb devices -l.\n' >&2
    "$adb" devices -l
    exit 1
fi
if [[ "$("$adb" -s "$ANDROID_SERIAL" get-state)" != device ]]; then
    printf 'Selected device is not ready: %s\n' "$ANDROID_SERIAL" >&2
    exit 1
fi
cd "$project_dir"
# Direct instrumentation avoids downloading Gradle's unified test platform.
bash ./gradlew --offline --no-daemon :app:assembleDebug :app:assembleDebugAndroidTest
"$adb" -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb" -s "$ANDROID_SERIAL" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Tests use isolated preference/key names, never the signed-in session.
classes=shafi.example.nexuschat.AndroidMessengerSessionStoreTest
expected=3
arguments=()
if [[ -n "${NEXUS_TEST_SERVER_URL:-}" ]]; then
    classes+=,shafi.example.nexuschat.AndroidLiveMessagingTest
    expected=4
    arguments=(-e messagingServer "$NEXUS_TEST_SERVER_URL")
fi
# Wake the screen without dismissing the keyguard or changing device settings.
"$adb" -s "$ANDROID_SERIAL" shell input keyevent KEYCODE_WAKEUP
result="$("$adb" -s "$ANDROID_SERIAL" shell am instrument -w -r \
    -e class "$classes" "${arguments[@]}" \
    shafi.example.nexuschat.test/androidx.test.runner.AndroidJUnitRunner)"
printf '%s\n' "$result"
# am instrument can return shell status zero even when a test or runner fails.
if [[ "$result" != *"OK ($expected tests)"* || "$result" == *"FAILURES!!!"* || "$result" == *"INSTRUMENTATION_FAILED"* ]]; then
    printf 'Device instrumentation did not pass all %s tests.\n' "$expected" >&2
    exit 1
fi
