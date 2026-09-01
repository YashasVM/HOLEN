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
- Root cause is a CI/test-environment mismatch, not a proven app regression: `disable-animations: true` sets the system animator scale to zero, while HOLEN intentionally treats animator scale zero as reduced-motion mode. In that mode `WelcomeStage` skips its 2.6-second cinematic delay and immediately advances, making the test's initial Welcome assertion impossible.
- Commit `37df23f1301c2cf5304a6c2537dbeca0daf78275` keeps Linux KVM and emulator-runner v2.38.0 but sets `disable-animations: false` so the onboarding-order test exercises the normal production cinematic path. Android CI run `33456901604` is validating it.
- After instrumentation CI is stable, return to first yt-dlp-managed download startup latency. Do not prewarm or parallelize Python/FFmpeg/aria2 extraction without device-side timing or another defensible structural benefit.

## Validation
- Android CI run `33452811379` passed the normal verify job: lint, JVM unit tests, APK builds, and 16 KB compatibility.
- Its Linux-KVM instrumentation artifact contained real XML/logcat output for four tests, with exactly one failure: `firstLaunchRunsCinematicOnboardingInOrder` timed out at `HolenInstrumentedTest.kt:52`; the other three tests passed.
- The onboarding implementation confirms why: `OnboardingFlow` reads `Settings.Global.ANIMATOR_DURATION_SCALE`; when it is zero, `WelcomeStage` does not execute the normal 2.6-second delay before advancing.
- No production Android code or instrumentation assertions were weakened for this fix; only the emulator environment was changed to preserve production motion behavior.

## Known risks
- Instrumentation CI is not considered stable until `33456901604` or a subsequent equivalent run passes the full suite reliably.
- Deferring FFmpeg trades lower metadata-path work for one-time FFmpeg initialization immediately before the first yt-dlp-managed download; no numeric speedup is claimed without device-side measurement.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
