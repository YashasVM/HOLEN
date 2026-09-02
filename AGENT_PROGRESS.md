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
- Reduced first-download tool initialization from the critical path by asynchronously prewarming FFmpeg then aria2c only after a successful FULL yt-dlp analysis.
- Proved the prewarm at the initialization boundary: Android CI run `33510436391` measured `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, and `process_launch_ms=1978`.
- Restored the live pre-existing Android CI safeguards after the local-extractor observability edit accidentally replaced unrelated workflow sections from stale content.
- Closed the startup micro-optimization investigation: Android CI run `33528825430` measured `process_launch_ms=1946`, `local_extract_ms=2225`, and `local_extract_overhead_ms=279`, showing about 87% of the deterministic localhost extraction path is the wrapper subprocess baseline.
- Added server-directed handling for HTTP 429 on Android direct downloads. `Retry-After` is preserved and retried only when valid and at most 30 seconds; Android CI run `33541067475` passed.
- Measured app-private storage in Android CI run `33546570973`: writing 64 MiB with the production 256 KiB copy buffer took `26 ms`, while the final `fsync` took `71 ms`.
- Repaired the localhost transfer probe with a debug-only cleartext manifest override; release cleartext policy remains unchanged.
- Made transfer evidence fail-closed in Android CI and closed the Java copy/resume bottleneck investigation. Android CI run `33563442686` passed and measured a 64 MiB fresh localhost transfer at `364 ms` and a 32 MiB HTTP Range resume at `160 ms`. These emulator-local measurements show the copy/append/resume path itself is not a credible reason to tune the 256 KiB buffer or worker count.
- Verified current yt-dlp aria2c integration already uses aggressive ordinary-HTTP defaults (`-x16 -j16 -s16`, 1 MiB minimum split), so HOLEN should not add redundant higher connection counts without real network evidence.
- Corrected the YouTube JS-runtime assumption: HOLEN already uses `youtubedl-android 0.18.1`, whose library bundles QuickJS `2025-04-26` and automatically supplies `--js-runtimes quickjs:<native-path>` on every yt-dlp execution. Added instrumentation assertions so CI fails if the wrapper stops configuring the packaged QuickJS runtime or its native file disappears; Android CI run `33573354344` passed.
- Added Android yt-dlp failure guidance for `Requested format is not available`, region restrictions, and generic unavailable-media failures; Android CI run `33577332071` passed.
- Closed the proposed stale-format-ID recovery investigation: HOLEN stores semantic choices (`BEST_MP4`, `MP4_1080`, `MP4_720`, audio variants), not yt-dlp format IDs. yt-dlp resolves the selector against current formats at download time, so there is no persisted stale format ID to remap. Automatic quality substitution would therefore add behavior/risk without solving the identified failure mode.
- Hardened imported cookie handling so a syntactically valid file containing only expired persistent cookies is no longer treated as configured or passed to yt-dlp. Session cookies (expiry `0`) remain valid and mixed files remain usable while at least one cookie is current; Android CI run `33585060641` passed.
- Rejected silent automatic cookie dropping. Upstream yt-dlp documents YouTube cookies as necessary mainly for account-gated content and warns that account use carries extra risk; real upstream reports also show authenticated extraction can expose fewer public formats. HOLEN now gives explicit cookie-isolation guidance on extractor HTTP 401/403 and requested-format failures instead of silently changing identity or quality.

## In progress
- Continue authenticated-download reliability by turning the new cookie-isolation guidance into a persisted Android-only “retry without cookies” path. The retry must survive process/background-service restarts, preserve the selected semantic format, and never affect private/age-restricted/members-only jobs unless the user explicitly chooses it.

## Validation
- Baseline Android CI run `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Second cold probe `33494800377`: `youtube_dl_ms=1019`, `ffmpeg_ms=1283`, `aria2c_ms=134`, `process_launch_ms=1957`, `total_ms=4393`.
- Prewarm proof `33510436391`: `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1978`.
- Restored-workflow/local-extractor run `33528825430`: `youtube_dl_ms=1088`, `ffmpeg_ms=1310`, `aria2c_ms=131`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1946`, `local_extract_ms=2225`, `local_extract_overhead_ms=279`.
- Android CI run `33541067475` passed the bounded `Retry-After` direct-download implementation.
- Storage run `33546570973`: `storage_write_ms=26`, `storage_fsync_ms=71` for 64 MiB.
- Transfer run `33553165159`: verify job passed, instrumentation failed because debug localhost cleartext was initially blocked; no transfer timing from this run is valid evidence.
- Repaired transfer run `33558047759`: verify and instrumentation passed; artifact metadata still omitted the transfer values.
- Fail-closed transfer run `33563442686`: Android verification and instrumentation passed; `transfer_fresh_ms=364` for 64 MiB and `transfer_resume_ms=160` for the remaining 32 MiB after a real `Range`/206 resume.
- QuickJS runtime-wiring run `33573354344`: Android verification and instrumentation passed.
- Unavailable-format/media classification run `33577332071`: Android verification passed for the new failure guidance and tests.
- Expired-cookie guard run `33585060641`: Android verification passed with JVM coverage for fully expired, session, and mixed cookie files.
- Cookie-isolation guidance (`4940a534`, `0082080d`): CI pending; JVM coverage now asserts that extractor 403 and requested-format failures preserve account-access guidance while explicitly suggesting a one-time no-cookie retry for public media.

## Known risks
- A user who performs a successful FULL analysis but never downloads pays the one-time FFmpeg/aria2 extraction cost in the background. Scope is intentionally limited to FULL analysis as the strongest existing download-intent signal.
- Hosted-emulator timings guide optimization but are not representative ARM-device performance claims; confirm on representative ARM hardware before advertising user-facing speedups.
- yt-dlp process launch remains structurally expensive under youtubedl-android because each execute call starts a fresh packaged-Python subprocess. Do not add dummy warm processes or migrate runtimes without representative-device evidence and a compatibility plan.
- YouTube's challenge behavior continues to evolve upstream. The previously suspected missing-runtime problem does not apply to HOLEN's current wrapper because QuickJS is already packaged/configured; future failures must be reproduced before attributing them to JS challenge support.
- The QuickJS instrumentation verifies wrapper command wiring and packaged native-file presence, not a live YouTube challenge, deliberately avoiding flaky public-network CI.
- `Requested format is not available` can mean current extractor output no longer satisfies the selected semantic quality/container constraints or source-side access changed. HOLEN intentionally does not silently substitute a different user-selected quality.
- YouTube account cookies rotate and upstream recommends using them only for content that actually needs authentication. HOLEN still applies a configured cookie file to yt-dlp requests. The new error text is only guidance; the actual persisted explicit no-cookie retry still needs implementation before this recovery becomes one-tap.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Direct-file rate-limit retries intentionally ignore `Retry-After` values above 30 seconds so one of the two download workers is not held for long server cooldowns.
- The localhost transfer probe intentionally isolates stream/copy/resume cost and does not exercise public HTTPS, mobile radios, server throttling, or end-to-end yt-dlp/aria2 behavior.
- The debug-only cleartext override exists solely for localhost instrumentation. Release builds retain the normal cleartext prohibition.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
