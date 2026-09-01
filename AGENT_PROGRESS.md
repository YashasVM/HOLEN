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
- Added a test-only cold-start timing harness for `YoutubeDL.init`, `FFmpeg.init`, `Aria2c.init`, and a minimal yt-dlp `--version` subprocess launch. CI records the report without a performance threshold because hosted-emulator timing is diagnostic, not a stable product benchmark.
- Initial timing run `33471554003` failed inside the new instrumentation path. The destructive probe is now explicitly opt-in and is skipped by the normal connected suite, preventing its runtime reset from interfering with functional tests. The timing report is also retained as a CI artifact for diagnosis.
- Production startup behavior remains unchanged: app idle warm-up initializes only Python/yt-dlp; a real yt-dlp-managed download then initializes FFmpeg and aria2c before launching yt-dlp.

## Validation
- Android CI run `33464291010` completed successfully on `d0a24f7eb1cb44a3b9906055025ea2244689b459`.
- The normal job passed lint, JVM unit tests, APK builds, and 16 KB native-library verification; Linux-KVM instrumentation passed.
- Startup timing run `33471554003` reached the real emulator instrumentation step but the new timing path failed before producing usable benchmark evidence. No speed claim is based on that run.
- Follow-up commits isolate the destructive probe from the normal suite and retain its text report; validation of that fix is pending in the new Android CI run.

## Known risks
- Deferring FFmpeg/aria2c keeps metadata startup lean but leaves their one-time initialization on the first yt-dlp-managed download critical path; no numeric latency claim is made until the corrected timing run completes.
- Hosted-emulator phase timings can identify a dominant extraction phase but are not a real-device performance claim and should be confirmed on representative ARM hardware before user-facing speed claims.
- Moving media-tool initialization earlier could trade download-start latency for unnecessary CPU/storage work on sessions that only inspect links, so any prewarming change needs evidence and a bounded trigger.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
