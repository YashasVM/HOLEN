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
- Current structure: app idle warm-up initializes only Python/yt-dlp; a real yt-dlp-managed download then initializes FFmpeg and aria2c before launching yt-dlp. This intentionally keeps heavy media-tool extraction off the metadata path.
- Upstream `youtubedl-android` documentation initializes YoutubeDL, FFmpeg, then Aria2c before downloads. It does not document concurrent initialization, so do not parallelize those extractors without evidence that their shared runtime setup is safe.
- Candidate next step is to obtain device-side phase timing for Python/yt-dlp readiness, FFmpeg init, aria2c init, and yt-dlp launch, or use another defensible measurement path before moving work earlier in the UI lifecycle.

## Validation
- Android CI run `33464291010` completed successfully on `d0a24f7eb1cb44a3b9906055025ea2244689b459`.
- The normal job passed lint, JVM unit tests, APK builds, and 16 KB native-library verification.
- Linux-KVM instrumentation passed; the instrumentation step completed successfully and retained its report artifact.
- No production Android code changed while resolving the final onboarding assertion; the corrected test now matches the current production UI contract.

## Known risks
- Deferring FFmpeg/aria2c keeps metadata startup lean but leaves their one-time initialization on the first yt-dlp-managed download critical path; no numeric latency claim is made without device-side measurement.
- Moving media-tool initialization earlier could trade download-start latency for unnecessary CPU/storage work on sessions that only inspect links, so any prewarming change needs evidence and a bounded trigger.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
