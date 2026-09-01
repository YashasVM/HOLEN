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
- Closed the remaining startup micro-optimization investigation with deterministic evidence rather than a runtime migration: Android CI run `33528825430` passed and measured `process_launch_ms=1946`, `local_extract_ms=2225`, and `local_extract_overhead_ms=279`. About 87% of the measured localhost extraction time is the fresh youtubedl-android subprocess baseline, so further HOLEN-side process-start micro-optimization is not justified under the current wrapper architecture.

## In progress
- Move performance work from wrapper startup into measurable Android download/extractor/network throughput and reliability, where HOLEN can materially affect behavior without replacing the yt-dlp runtime architecture.
- Audit representative ordinary HTTP and fragmented download paths for bottlenecks, interruption/retry/resume behavior, progress overhead, and unnecessary storage/copy costs before changing concurrency or downloader flags.

## Validation
- Baseline Android CI run `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Second cold probe `33494800377`: `youtube_dl_ms=1019`, `ffmpeg_ms=1283`, `aria2c_ms=134`, `process_launch_ms=1957`, `total_ms=4393`.
- Prewarm proof `33510436391`: `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1978`.
- Restored-workflow/local-extractor run `33528825430` passed Android verification and Linux-KVM instrumentation. Artifact metadata reports `youtube_dl_ms=1088`, `ffmpeg_ms=1310`, `aria2c_ms=131`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1946`, `local_extract_ms=2225`, `local_extract_overhead_ms=279`.
- The localhost probe uses a tiny loopback `video/mp4` and a real `YoutubeDL.execute` metadata path, removing public-network variance. Its 279 ms derived overhead is diagnostic decomposition from a hosted x86_64 emulator, not a representative ARM-device latency claim.
- Android CI run `33523235345` remains invalid product-performance evidence because it failed before Android execution after the accidental workflow rewrite; it is superseded by the restored green run above.

## Known risks
- A user who performs a successful FULL analysis but never downloads pays the one-time FFmpeg/aria2 extraction cost in the background. Scope is intentionally limited to FULL analysis as the strongest existing download-intent signal.
- If the user queues immediately after analysis, the download may still wait for some initialization; the existing `initMutex` makes this a join rather than duplicate extraction.
- Hosted-emulator timings guide optimization but are not representative ARM-device performance claims; confirm on representative ARM hardware before advertising user-facing speedups.
- yt-dlp process launch remains structurally expensive under youtubedl-android because each execute call starts a fresh packaged-Python subprocess. Do not add dummy warm processes or migrate runtimes without representative-device evidence and a compatibility plan.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid extra process-level retries without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
