#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

mkdir -p app/build/reports/startup

current_stage="setup"
trap 'status=$?; if [[ $status -ne 0 ]]; then echo "Instrumentation CI failed during: $current_stage" >&2; fi' EXIT

current_stage="assemble-debug"
./gradlew assembleDebug assembleDebugAndroidTest

current_stage="start-emulator"
printf 'no\n' | avdmanager create avd \
  --force \
  --name holen-ci \
  --package 'system-images;android-35;google_apis;x86_64' \
  --device 'pixel_6' >/dev/null

emulator \
  -avd holen-ci \
  -no-window \
  -gpu swiftshader_indirect \
  -noaudio \
  -no-boot-anim \
  -camera-back none \
  -camera-front none \
  >/tmp/holen-emulator.log 2>&1 &

adb wait-for-device
boot_completed=""
for _ in $(seq 1 180); do
  boot_completed="$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [[ "$boot_completed" == "1" ]]; then
    break
  fi
  sleep 2
done
if [[ "$boot_completed" != "1" ]]; then
  echo 'Android emulator did not finish booting.' >&2
  cat /tmp/holen-emulator.log >&2 || true
  exit 1
fi
adb shell input keyevent 82 || true

current_stage="app-startup-test"
adb logcat -c
./gradlew connectedEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yashasvm.holen.AppStartupTimingTest \
  2>&1 | tee app/build/reports/startup/app-startup-gradle.txt
current_stage="app-startup-report"
adb logcat -d -s HOLENStartupTiming:I '*:S' \
  | tee app/build/reports/startup/app-startup-logcat.txt
grep -o 'process_start_to_main_create_ms=[0-9][0-9]* process_start_to_first_frame_ms=[0-9][0-9]*' app/build/reports/startup/app-startup-logcat.txt \
  | tail -n 1 \
  | tee app/build/reports/startup/app-startup-timing.txt
test -s app/build/reports/startup/app-startup-timing.txt
grep -q 'process_start_to_main_create_ms=[0-9][0-9]*' app/build/reports/startup/app-startup-timing.txt
grep -q 'process_start_to_first_frame_ms=[0-9][0-9]*' app/build/reports/startup/app-startup-timing.txt
cat app/build/reports/startup/app-startup-timing.txt >> "$GITHUB_STEP_SUMMARY"

current_stage="engine-startup-test"
adb logcat -c
./gradlew connectedEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yashasvm.holen.EngineStartupTimingTest \
  -Pandroid.testInstrumentationRunnerArguments.holenEngineStartupTiming=true \
  2>&1 | tee app/build/reports/startup/engine-startup-gradle.txt
current_stage="engine-startup-report"
adb logcat -d -s HOLENEngineTiming:I '*:S' \
  | tee app/build/reports/startup/instrumentation-logcat.txt
grep 'HOLEN Android engine timing' app/build/reports/startup/instrumentation-logcat.txt \
  | tail -n 1 \
  | sed 's/^.*HOLEN Android engine timing/HOLEN Android engine timing/' \
  | tee app/build/reports/startup/engine-startup-timing.txt
test -s app/build/reports/startup/engine-startup-timing.txt
grep -q 'youtube_dl_init_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'ffmpeg_init_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'aria2_init_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'process_launch_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'extractor_overhead_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'storage_write_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'storage_fsync_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_bytes=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_fresh_ms=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_resume_offset_bytes=' app/build/reports/startup/engine-startup-timing.txt
grep -q 'transfer_resume_ms=' app/build/reports/startup/engine-startup-timing.txt
cat app/build/reports/startup/engine-startup-timing.txt >> "$GITHUB_STEP_SUMMARY"

current_stage="repeat-yt-dlp-launch-test"
adb logcat -c
./gradlew connectedEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yashasvm.holen.RepeatedYtDlpLaunchTimingTest \
  -Pandroid.testInstrumentationRunnerArguments.holenRepeatedLaunchTiming=true \
  2>&1 | tee app/build/reports/startup/repeated-yt-dlp-launch-gradle.txt
current_stage="repeat-yt-dlp-launch-report"
adb logcat -d -s HOLENRepeatLaunch:I '*:S' \
  | tee app/build/reports/startup/repeated-yt-dlp-launch-logcat.txt
grep -o 'process_launch_first_ms=[0-9][0-9]* process_launch_repeat_ms=[0-9][0-9]*' app/build/reports/startup/repeated-yt-dlp-launch-logcat.txt \
  | tail -n 1 \
  | tee app/build/reports/startup/repeated-yt-dlp-launch-timing.txt
test -s app/build/reports/startup/repeated-yt-dlp-launch-timing.txt
grep -q 'process_launch_first_ms=[0-9][0-9]*' app/build/reports/startup/repeated-yt-dlp-launch-timing.txt
grep -q 'process_launch_repeat_ms=[0-9][0-9]*' app/build/reports/startup/repeated-yt-dlp-launch-timing.txt
cat app/build/reports/startup/repeated-yt-dlp-launch-timing.txt >> "$GITHUB_STEP_SUMMARY"

current_stage="ejs-remote-component-test"
adb logcat -c
./gradlew connectedEmulatorDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.yashasvm.holen.EjsRemoteComponentInstrumentedTest \
  -Pandroid.testInstrumentationRunnerArguments.holenEjsRemoteProbe=true \
  2>&1 | tee app/build/reports/startup/ejs-remote-component-gradle.txt
current_stage="ejs-remote-component-report"
adb logcat -d -s HOLENEjsProbe:I '*:S' \
  | tee app/build/reports/startup/ejs-remote-component-logcat.txt
grep -o 'first_ms=[0-9][0-9]* first_exit=-\?[0-9][0-9]* first_remote_signal=\(true\|false\) first_cache_signal=\(true\|false\) first_upstream_blocked=\(true\|false\) cached_ms=[0-9][0-9]* cached_exit=-\?[0-9][0-9]* cached_remote_signal=\(true\|false\) cached_cache_signal=\(true\|false\) cached_upstream_blocked=\(true\|false\)' app/build/reports/startup/ejs-remote-component-logcat.txt \
  | tail -n 1 \
  | tee app/build/reports/startup/ejs-remote-component-summary.txt
test -s app/build/reports/startup/ejs-remote-component-summary.txt
grep -q 'first_ms=[0-9][0-9]*' app/build/reports/startup/ejs-remote-component-summary.txt
grep -q 'cached_ms=[0-9][0-9]*' app/build/reports/startup/ejs-remote-component-summary.txt
grep -q 'first_upstream_blocked=\(true\|false\)' app/build/reports/startup/ejs-remote-component-summary.txt
grep -q 'cached_upstream_blocked=\(true\|false\)' app/build/reports/startup/ejs-remote-component-summary.txt
grep 'HOLEN EJS \(first\|cached\) diagnostics:' app/build/reports/startup/ejs-remote-component-logcat.txt \
  | tail -n 2 \
  > app/build/reports/startup/ejs-remote-component-diagnostics.txt || true
{
  cat app/build/reports/startup/ejs-remote-component-summary.txt
  if [[ -s app/build/reports/startup/ejs-remote-component-diagnostics.txt ]]; then
    echo
    echo 'EJS probe diagnostics:'
    echo '```text'
    cat app/build/reports/startup/ejs-remote-component-diagnostics.txt
    echo '```'
  fi
} >> "$GITHUB_STEP_SUMMARY"

current_stage="full-instrumentation-suite"
./gradlew connectedEmulatorDebugAndroidTest \
  2>&1 | tee app/build/reports/startup/full-instrumentation-gradle.txt

current_stage="complete"
