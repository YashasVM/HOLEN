# Agent progress

## Completed
- Created and maintained the long-running `agent-dev` branch while leaving `main` untouched.
- Ported the validated Android updater version-normalization fix from former PR #18.
- Added conservative direct-download resume fallback using strong Last-Modified/Date validation when no ETag exists; weak/malformed validators still disable resume.
- Added bounded direct-download retry for transient transport failures and HTTP 408/500/502/503/504 while preserving resumable staging.
- Added actionable yt-dlp HTTP failure classification for auth/access, unavailable media, rate limits, and temporary source failures.
- Removed FFmpeg extraction from metadata warm-up so first metadata analysis no longer waits on media tooling it does not need.
- Hardened same-process engine reset/update failure handling so destructive runtime resets fail with explicit restart guidance instead of reusing stale singleton initialization state.
- Kept aria2c as the ordinary-transfer downloader while forcing DASH/HLS through yt-dlp native downloading, matching the upstream mitigation for GHSA-vx4q-3cr2-7cg2 / CVE-2026-50574.
- Fixed restart-required engine failures being misclassified as generic network failures.
- Added a blocking Android instrumentation CI job on Ubuntu/KVM, retained failure reports, and aligned the onboarding test with the production creator-credit contract.
- Android CI run `33464291010` passed both the normal verification job and the Linux-KVM instrumentation job after the test alignment; the instrumentation gate is now working end to end.
- Verified Android lint, JVM tests, emulator/ARM64/ARMv7/universal APK builds, 16 KB native-library compatibility, and the connected instrumentation suite for the completed changes above.

## In progress
- Reduce first yt-dlp-managed download startup latency without regressing metadata responsiveness or doing generic app-start prewarming.
- The cold-start timing harness produces fail-closed measurements for `YoutubeDL.init`, `FFmpeg.init`, `Aria2c.init`, and a minimal yt-dlp `--version` process launch.
- Baseline Android CI run `33484712612` measured on the hosted API-35 x86_64 emulator: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- A second cold probe in Android CI run `33494800377` measured `youtube_dl_ms=1019`, `ffmpeg_ms=1283`, `aria2c_ms=134`, `process_launch_ms=1957`, `total_ms=4393`, showing the baseline is repeatable enough to guide this optimization.
- FFmpeg + aria2c one-time initialization accounted for 1.417-1.461 s across those runs, roughly one third of measured cold wrapper startup, while yt-dlp process launch remained the largest individual phase.
- Commit `94ab5caa` starts FFmpeg then aria2c initialization asynchronously only after a successful FULL yt-dlp analysis. QUICK/shared-link analysis and ordinary app idle remain lean, and the existing engine operation gate/init mutex serialize initialization against updates, resets, and immediate downloads.
- The prewarm is best-effort: failures are deliberately deferred to the real download path, where existing actionable startup errors are shown. This avoids surfacing a background error before the user has chosen to download.
- The startup probe now also records `post_prewarm_tool_reentry_ms`: after one cold FFmpeg+aria2 initialization, it immediately measures invoking those wrapper initializers again. HOLEN's real post-prewarm `ensureInitialized` path returns even earlier because its in-memory flags avoid those calls entirely. CI is configured to fail if this new measurement is missing.

## Validation
- Android CI run `33494800377` passed the normal verification job and Linux-KVM instrumentation for the production prewarm commit. Lint/tests/APK builds/16 KB verification and the connected suite all remained green.
- The same run retained a non-empty timing report and reproduced the earlier cold-start total within 4 ms (`4393` vs `4389` ms), so the diagnostic baseline is stable across these two hosted-emulator runs.
- Fresh Android CI for commits `6905cfbc` / `f9a847ce` is pending and must produce a non-empty `post_prewarm_tool_reentry_ms` value before the prewarm benefit is considered measured at the wrapper boundary.
- The measurement is diagnostic evidence from a hosted x86_64 emulator, not a user-facing ARM-device benchmark.
- Do not claim a user-visible speedup yet: the new boundary probe measures the remaining wrapper initialization cost after completed prewarm, but it does not include real user think-time or network/download launch latency.
- The production code diff remains intentionally narrow: one guarded background prewarm path in `YtDlpEngine`; no web/CLI behavior, format selection, downloader arguments, release metadata, or app-start warm-up changed.

## Known risks
- A user who performs a successful FULL analysis but never downloads will now pay the one-time FFmpeg/aria2 extraction cost in the background. Scope is intentionally limited to FULL analysis because that is the strongest existing download-intent signal.
- If the user queues immediately after analysis, the download may still wait for some or all of initialization; the existing `initMutex` makes this a join rather than duplicate extraction.
- Hosted-emulator timings can guide optimization but are not representative ARM-device performance claims; confirm on representative ARM hardware before advertising a user-facing speedup.
- yt-dlp process launch remains the single largest measured startup component and may be mostly intrinsic to Python/yt-dlp startup; do not add a dummy warm process without measurement.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
