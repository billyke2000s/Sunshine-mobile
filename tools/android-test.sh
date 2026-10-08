#!/usr/bin/env bash
# Capture logs before the emulator runner shuts the emulator down, including failures.
set -u
gradle --no-daemon -PemulatorTests :app:connectedDebugAndroidTest
test_status=$?
timeout 15s adb logcat -d > android-logcat.txt || true
exit "$test_status"
