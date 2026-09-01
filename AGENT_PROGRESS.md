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
- Added a blocking Android instrumentation CI job on Ubuntu/KVM and verified lint, JVM tests, emulator/ARM64/ARMv7/universal APK builds, 16 KB native-library compatibility, and connected instrumentation for the maintained branch.
- Reduced first-download tool initialization from the critical path by asynchronously prewarming FFmpeg then aria2c only after a successful FULL yt-dlp analysis. QUICK/shared-link analysis and ordinary app idle remain lean.
- Proved the prewarm at the initialization boundary: Android CI run `33510436391` measured `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, and `process_launch_ms=1978`.
- Restored the live pre-existing Android CI safeguards after the local-extractor observability edit accidentally replaced unrelated workflow sections from stale content. Signing enforcement, Android 37.1 setup, pinned emulator-runner, 16 KB verifier behavior, branch/path filters, and artifact behavior are restored.
- Closed the remaining startup micro-optimization investigation with deterministic evidence: Android CI run `33528825430` measured `process_launch_ms=1946`, `local_extract_ms=2225`, and `local_extract_overhead_ms=279`, showing about 87% of the deterministic localhost extraction path is the wrapper subprocess baseline.
- Added server-directed handling for HTTP 429 on Android direct downloads. `Retry-After` is preserved and retried only when valid and at most 30 seconds; Android CI run `33541067475` passed.

## In progress
- Measure Android direct-transfer storage cost before changing buffers or concurrency. The opt-in emulator probe now writes 64 MiB using the production 256 KiB transfer buffer and records write time separately from the final `fsync`, with both values exposed in CI artifact metadata.
- After storage timing is validated, extend the deterministic transfer work toward fresh-download and interrupted Range-resume paths without depending on public-network conditions.

## Validation
- Baseline Android CI run `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Second cold probe `33494800377`: `youtube_dl_ms=1019`, `ffmpeg_ms=1283`, `aria2c_ms=134`, `process_launch_ms=1957`, `total_ms=4393`.
- Prewarm proof `33510436391`: `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1978`.
- Restored-workflow/local-extractor run `33528825430`: `youtube_dl_ms=1088`, `ffmpeg_ms=1310`, `aria2c_ms=131`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1946`, `local_extract_ms=2225`, `local_extract_overhead_ms=279`.
- Android CI run `33541067475` passed the bounded `Retry-After` direct-download implementation.
- The 64 MiB storage probe is diagnostic hosted-emulator evidence only. It does not represent ARM-device storage throughput and does not justify a speedup claim by itself.

## Known risks
- A user who performs a successful FULL analysis but never downloads pays the one-time FFmpeg/aria2 extraction cost in the background. Scope is intentionally limited to FULL analysis as the strongest existing download-intent signal.
- Hosted-emulator timings guide optimization but are not representative ARM-device performance claims; confirm on representative ARM hardware before advertising user-facing speedups.
- yt-dlp process launch remains structurally expensive under youtubedl-android because each execute call starts a fresh packaged-Python subprocess. Do not add dummy warm processes or migrate runtimes without representative-device evidence and a compatibility plan.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Direct-file rate-limit retries intentionally ignore `Retry-After` values above 30 seconds so one of the two download workers is not held for long server cooldowns; those cases remain actionable failures for the user to retry later.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
