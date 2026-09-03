#!/usr/bin/env bash

set -euo pipefail

print_emulator_diagnostics() {
  local status=$?
  if [[ "$status" -ne 0 ]]; then
    adb get-state || true
    adb shell getprop sys.boot_completed || true
    adb shell service check package || true
    adb logcat -d -t 200 || true
  fi
  exit "$status"
}

trap print_emulator_diagnostics EXIT

timeout 30s adb wait-for-device

for attempt in $(seq 1 36); do
  boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [[ "$boot_completed" == "1" ]] &&
    adb shell service check package 2>/dev/null | grep -q "found"; then
    break
  fi

  if [[ "$attempt" -eq 36 ]]; then
    echo "Android Package Manager did not become ready" >&2
    exit 1
  fi
  sleep 5
done

adb shell pm path android
adb shell am get-current-user
./gradlew --no-daemon --max-workers=1 connectedDebugAndroidTest
