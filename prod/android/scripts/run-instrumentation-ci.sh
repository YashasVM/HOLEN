#!/usr/bin/env bash
set -euo pipefail

mkdir -p app/build/reports/startup

current_stage="bootstrap"

collect_instrumentation_diagnostics() {
  status=$?
  trap - EXIT
  diagnostics=app/build/reports/startup/instrumentation-diagnostics.txt
  {
    echo "script_exit_status=$status"
    echo "failed_stage=$current_stage"
    echo "=== adb devices ==="
    adb devices -l || true
    echo "=== target package ==="
    adb shell dumpsys package com.yashasvm.holen 2>/dev/null | head -n 80 || true
    echo "=== generated android-test outputs ==="
    find app/build \( -path '*androidTest*' -o -path '*androidTests*' \) 2>/dev/null | sort | tail -n 200 || true
  } > "$diagnostics" 2>&1
  adb logcat -d > app/build/reports/startup/instrumentation-logcat.txt 2>&1 || true
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      echo "### Android instrumentation diagnostics"
      echo '```text'
      cat "$diagnostics"
      echo '```'
    } >> "$GITHUB_STEP_SUMMARY" || true
  fi
  exit "$status"
}
trap collect_instrumentation_diagnostics EXIT

current_stage="configure-emulator"
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings put global animator_duration_scale 1.0
test "$(adb shell settings get global animator_duration_scale | tr -d '\r')" = "1.0"

current_stage="app-startup-test"
adb logcat -c
./gradlew connectedEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yashasvm.holen.AppStartupTimingTest \
  -Pandroid.testInstrumentationRunnerArguments.holenAppStartupTiming=true \
  2>&1 | tee app/build/reports/startup/app-startup-gradle.txt
current_stage="app-startup-report"
adb logcat -d -s HOLENAppStartup:I '*:S' \
  | tee app/build/reports/startup/app-startup-logcat.txt
grep -o 'app_home_ms=[0-9][0-9]*' app/build/reports/startup/app-startup-logcat.txt \
  | tail -n 1 \
  | tee app/build/reports/startup/app-startup-timing.txt
test -s app/build/reports/startup/app-startup-timing.txt
grep -q '^app_home_ms=[0-9][0-9]*$' app/build/reports/startup/app-startup-timing.txt
cat app/build/reports/startup/app-startup-timing.txt >> "$GITHUB_STEP_SUMMARY"

current_stage="engine-startup-test"
adb logcat -c
./gradlew connectedEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yashasvm.holen.EngineStartupTimingTest \
  -Pandroid.testInstrumentationRunnerArguments.holenStartupTiming=true \
  2>&1 | tee app/build/reports/startup/engine-startup-gradle.txt
current_stage="engine-startup-report"
adb logcat -d -s HOLENStartupTiming:I '*:S' \
  | tee app/build/reports/startup/engine-startup-timing.txt
test -s app/build/reports/startup/engine-startup-timing.txt
grep -q 'youtube_dl_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'ffmpeg_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'aria2c_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'post_prewarm_tool_reentry_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'process_launch_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'local_extract_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'local_extract_overhead_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'storage_write_bytes=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'storage_write_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'storage_fsync_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_bytes=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_fresh_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_resume_offset_bytes=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_resume_ms=' app/build/reports/startup/engine-startup-timing.txt
cat app/build/reports/startup/engine-startup-timing.txt >> "$GITHUB_STEP_SUMMARY"

current_stage="full-instrumentation-suite"
./gradlew connectedEmulatorDebugAndroidTest \
  2>&1 | tee app/build/reports/startup/full-instrumentation-gradle.txt

current_stage="complete"
