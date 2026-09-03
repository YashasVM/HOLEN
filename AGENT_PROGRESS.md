# Agent progress

## Completed since weekly review
- Kept all autonomous Android work on `agent-dev`; `main` remains untouched.
- Hardened direct-file downloads with conservative resume validation, bounded transient retries, and valid short `Retry-After` handling for HTTP 429.
- Improved yt-dlp failure classification for authentication/access, rate limits, unavailable/region-restricted media, requested-format failures, post-processing failures, common transient transport failures, and incomplete-fragment/empty-output failures without silently substituting quality.
- Kept ordinary HTTP transfers on aria2c while routing DASH/HLS through yt-dlp native fragment downloading, matching the upstream mitigation for GHSA-vx4q-3cr2-7cg2 / CVE-2026-50574.
- Verified youtubedl-android 0.18.1 already bundles/configures QuickJS; CI guards that runtime wiring rather than adding a redundant JS runtime.
- Removed FFmpeg from metadata initialization and prewarm download tooling only after successful FULL analysis.
- Hardened imported cookies so fully expired persistent-cookie files are not treated as configured.
- Completed explicit `Retry without cookies` recovery for eligible failed public-media jobs. The per-job authentication policy is durable across process/service restart, ordinary Retry restores configured cookies, account/age/members-only/direct/cancelled jobs are excluded, and stale policy state is cleaned with job/history removal.
- End-to-end Compose coverage for the guarded no-cookie action is green: Android CI `33651634112` passed after deterministic setup and scrolling to the failed-job card before asserting.
- Completed a working launch-to-rendered-home startup probe. Android CI `33705752404` passed verify and instrumentation twice on the same commit; the probe produced `app_home_ms=2293` and `app_home_ms=3312`.
- Added explicit FFmpeg/yt-dlp post-processing failure classification; Android CI `33713261388` passed.
- Added transient yt-dlp transport classification for connection reset/abort/refusal, remote disconnect, broken pipe, DNS resolution, unreachable network, webpage/API fetch, and SSL EOF failures; Android CI `33720955973` and generic CI passed.
- Added incomplete-fragment/empty-output failure classification with focused precedence coverage; Android CI `33730714220` and generic CI passed.
- Enforced fragment integrity for yt-dlp DASH/HLS downloads with `--abort-on-unavailable-fragments`; Android CI `33736269904` passed. Missing fragments now fail instead of silently finalizing known-incomplete media.

## In progress
- Validate fragment integrity end-to-end against the packaged Android yt-dlp runtime. `FragmentIntegrityInstrumentedTest` serves a deterministic localhost HLS playlist whose second segment returns HTTP 404, then requires yt-dlp to request the missing segment, fail with `--abort-on-unavailable-fragments`, and leave no finalized media. Android CI `33741738053` is validating commit `6879799b`.
- Review yt-dlp transfer retry policy using real failure evidence before changing it. HOLEN pins `--retries 3` and `--fragment-retries 3` while current yt-dlp defaults are higher. Do not increase retries or add delays solely to match upstream; more retries/sleeps can increase failure latency, bandwidth use, and rate-limit pressure.
- Startup measurement is technically reliable, but hosted-emulator absolute timing is too noisy for small optimization claims: the two green measurements differ by 1019 ms (~44% of the lower result). Use representative-device or phase-relative evidence before production startup optimization.

## Validation / performance evidence
- Engine baseline `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Post-prewarm proof `33510436391`: `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1978`.
- Local extraction `33528825430`: `process_launch_ms=1946`, `local_extract_ms=2225`, `local_extract_overhead_ms=279`; the wrapper subprocess baseline dominates this deterministic path.
- Storage probe `33546570973`: 64 MiB write `26 ms`, final `fsync` `71 ms`.
- Transfer probe `33563442686`: 64 MiB localhost fresh transfer `364 ms`; 32 MiB HTTP Range resume `160 ms`. This does not justify changing the 256 KiB copy buffer or worker count.
- QuickJS wiring `33573354344`, cookie expiry `33585060641`, cookie-isolation eligibility `33601355781`, persisted auth policy `33606011186`, execution wiring `33611780875`, explicit requeue `33622415323`, tightened exclusions `33627146686`, production UI `33633640767`, and final end-to-end UI run `33651634112` all passed their relevant Android CI validation.
- Startup probe `33705752404`: verify passed lint/tests/build, release APK assembly, and 16 KB verification; instrumentation passed twice. Launch-to-rendered-home was `2293 ms` then `3312 ms`. The same rerun measured `youtube_dl_ms=1193`, `ffmpeg_ms=1255`, `aria2c_ms=134`, `process_launch_ms=1908`, 64 MiB storage write `27 ms` + `fsync` `63 ms`, fresh localhost transfer `307 ms`, and resumed transfer `111 ms`.
- Post-processing classification `2eb9578b`: Android CI `33713261388` passed on 2026-09-03.
- Transport classification `c0202610`: Android CI `33720955973` and generic CI passed on 2026-09-03.
- Fragment-failure classification/tests through `aa767490`: Android CI `33730714220` and generic CI passed on 2026-09-03.
- Fragment-integrity production policy `44194c63`: Android CI `33736269904` passed on 2026-09-03.

## Known risks / review points
- `--abort-on-unavailable-fragments` favors integrity over partial success. Some streams with a genuinely tolerable missing segment now fail; the user can re-analyze/retry/update the engine instead of unknowingly receiving incomplete media. The deterministic packaged-runtime missing-fragment test is still validating this behavior end-to-end.
- Hosted-emulator timings are useful for large regressions but too variable for micro-optimization claims. Confirm material gains on representative ARM hardware.
- yt-dlp process launch is structurally expensive under youtubedl-android because each execute call starts a packaged-Python subprocess. Do not add dummy warm processes or migrate runtimes without device evidence and a compatibility plan.
- HOLEN explicitly uses three yt-dlp transfer/fragment retries. Changing retry count/backoff needs representative failure evidence because extra retries can worsen rate limits and long-tail failure time.
- A successful FULL analysis can trigger one-time FFmpeg/aria2 prewarm even if the user never downloads; scope is intentionally limited to the strongest existing download-intent signal.
- YouTube challenge/auth behavior continues to evolve. Normal jobs use configured cookies; no-cookie retry remains explicit and user-driven and never silently changes selected quality.
- DASH/HLS intentionally do not use aria2c; ordinary HTTP transfers do.
- `main` remains intentionally user-controlled and untouched by autonomous maintenance.

## Weekly review
Compare `agent-dev` against `main`, inspect the latest Android CI for the newest Android code commit, review this file, and merge only the changes you want.
