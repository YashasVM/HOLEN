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
- Verified Android lint, JVM tests, emulator/ARM64/ARMv7/universal APK builds, and 16 KB native-library compatibility for the completed production-code changes above.

## In progress
- Make the existing Android instrumentation suite a reliable blocking CI gate.
- Intel-macOS emulator attempts stalled before Gradle connected-test output, so instrumentation was moved to Ubuntu with KVM. Linux KVM reaches the actual suite quickly and produces connected-test reports.
- Android CI run `33460480923` passed the normal Android verification job and executed all four instrumentation tests; three passed and only `firstLaunchRunsCinematicOnboardingInOrder` failed.
- The retained report showed the failure precisely at `HolenInstrumentedTest.kt:58`: the test expected `Made by @yashas.vm` during the Welcome stage. Current production code intentionally renders `persistent-creator-credit` only when onboarding is complete, and explicitly documents that attribution belongs to the download home rather than setup/onboarding.
- Commit `d0a24f7eb1cb44a3b9906055025ea2244689b459` therefore fixes the stale instrumentation expectation: onboarding now asserts that the persistent creator credit is absent during Welcome/About instead of incorrectly requiring it. No production UI behavior was changed.
- After instrumentation CI is stable, return to first yt-dlp-managed download startup latency. Do not prewarm or parallelize Python/FFmpeg/aria2 extraction without device-side timing or another defensible structural benefit.

## Validation
- Android CI run `33460480923` passed lint, JVM unit tests, APK builds, and 16 KB compatibility in the normal verify job.
- Linux-KVM instrumentation completed in about three and a half minutes and retained a 64,590-byte report artifact, so emulator boot/Gradle execution is functioning.
- The report recorded the exact failed selector: `Made by @yashas.vm` was absent during Welcome, which matches the current production implementation rather than indicating an app regression.
- The test fix strengthens the intended contract by explicitly asserting that `persistent-creator-credit` does not exist during onboarding; it does not weaken production code or bypass the instrumentation gate.

## Known risks
- Instrumentation CI is not considered stable until the test-alignment commit passes the full suite on Linux KVM.
- Deferring FFmpeg trades lower metadata-path work for one-time FFmpeg initialization immediately before the first yt-dlp-managed download; no numeric speedup is claimed without device-side measurement.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
