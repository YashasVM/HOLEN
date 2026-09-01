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
- Measure and reduce first yt-dlp-managed download startup latency without regressing metadata responsiveness or adding speculative prewarming.
- The cold-start timing harness now produces durable, fail-closed evidence for `YoutubeDL.init`, `FFmpeg.init`, `Aria2c.init`, and a minimal yt-dlp `--version` process launch.
- Android CI run `33484712612` passed normal verification and Linux-KVM instrumentation and retained a non-empty startup timing report.
- Measured on the hosted API-35 x86_64 emulator: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- The largest measured phase is yt-dlp process launch (1.944 s). FFmpeg + aria2c one-time initialization together account for 1.461 s, about one third of the measured cold wrapper startup.
- Production behavior is still unchanged: app idle warm-up initializes only Python/yt-dlp; a real yt-dlp-managed download initializes FFmpeg and aria2c before launching yt-dlp.
- The current candidate optimization is bounded post-analysis prewarming: after a successful FULL yt-dlp analysis (strong download intent), initialize FFmpeg then aria2c asynchronously while the user reviews download options. QUICK/shared-link analysis and ordinary app idle should remain lean. Upstream youtubedl-android still documents the same sequential YoutubeDL -> FFmpeg -> Aria2c initialization order, so do not parallelize these initializers without evidence.

## Validation
- Android CI run `33484712612` succeeded end to end after the timing path was changed to fail closed when any phase is missing.
- The retained `HOLEN-android-instrumentation-reports` artifact was 61,394 bytes; `reports/startup/engine-startup-timing.txt` was 204 bytes and contained all four expected phase values above.
- The measurement is diagnostic evidence from a hosted x86_64 emulator, not a user-facing ARM-device benchmark or a claimed speedup.
- No production startup optimization has been committed yet from these measurements; therefore there is no before/after performance claim.

## Known risks
- Deferring FFmpeg/aria2c keeps metadata startup lean but leaves their one-time initialization on the first yt-dlp-managed download critical path.
- Hosted-emulator phase timings can identify a dominant extraction phase but are not a real-device performance claim and should be confirmed on representative ARM hardware before user-facing speed claims.
- Moving media-tool initialization earlier could trade download-start latency for unnecessary CPU/storage work on sessions that only inspect links. Any prewarm should therefore be limited to successful FULL analysis, not generic app idle or QUICK analysis.
- yt-dlp process launch remains the single largest measured startup component and may be mostly intrinsic to starting Python/yt-dlp; do not add a dummy process launch merely to warm caches unless a before/after measurement proves material benefit.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.