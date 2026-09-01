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
- Android CI run `33452811379` on emulator-runner v2.38.0 executed all four tests: `tutorialScreenshotsArePortraitBitmaps`, `sqliteSchemaIsCreatedAtCurrentVersion`, and `interruptedJobsAreRequeuedAndClaimedAtomically` passed; only `firstLaunchRunsCinematicOnboardingInOrder` failed after 5 seconds.
- `disable-animations: false` alone did not clear that failure: run `33456901604` still failed only the instrumentation job while the normal Android verification job remained green. The emulator reached the test step in about three minutes and retained a real instrumentation report artifact.
- HOLEN intentionally treats global animator scale zero as reduced-motion mode; in that mode `WelcomeStage` immediately advances instead of waiting 2.6 seconds. Because emulator images can retain zero animation scales even when the runner is told not to disable animations, commit `276e8d9da89ba008dc5d2cd5f7901a8f62f35c6c` now explicitly sets window/transition/animator scales to `1.0` and verifies animator scale before launching Gradle instrumentation.
- After instrumentation CI is stable, return to first yt-dlp-managed download startup latency. Do not prewarm or parallelize Python/FFmpeg/aria2 extraction without device-side timing or another defensible structural benefit.

## Validation
- Android CI run `33456901604` passed the normal verify job: lint, JVM unit tests, APK builds, and 16 KB compatibility; only instrumentation failed.
- The Linux-KVM instrumentation step completed in roughly three minutes and uploaded a 61,519-byte report artifact, confirming the emulator/Gradle path is functioning rather than hanging during boot.
- The onboarding implementation reads `Settings.Global.ANIMATOR_DURATION_SCALE`; when it is zero, `WelcomeStage` skips the normal 2.6-second delay before advancing.
- No production Android code or instrumentation assertions were weakened. The latest change only makes the emulator's motion configuration deterministic and fails early if the intended animator scale cannot be applied.

## Known risks
- Instrumentation CI is not considered stable until the explicit-motion-scale run or a subsequent equivalent run passes the full suite reliably.
- Deferring FFmpeg trades lower metadata-path work for one-time FFmpeg initialization immediately before the first yt-dlp-managed download; no numeric speedup is claimed without device-side measurement.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
