# Agent progress

## Completed since weekly review
- Kept all autonomous Android work on `agent-dev`; `main` remains untouched.
- Hardened direct-file downloads with conservative resume validation, bounded transient retries, and valid short `Retry-After` handling for HTTP 429.
- Improved yt-dlp failure classification for authentication/access, rate limits, unavailable/region-restricted media, and requested-format failures without silently substituting quality.
- Kept ordinary HTTP transfers on aria2c while routing DASH/HLS through yt-dlp native fragment downloading, matching the upstream mitigation for GHSA-vx4q-3cr2-7cg2 / CVE-2026-50574.
- Verified youtubedl-android 0.18.1 already bundles/configures QuickJS; CI now guards that runtime wiring rather than adding a redundant JS runtime.
- Removed FFmpeg from metadata initialization and prewarm download tooling only after successful FULL analysis.
- Hardened imported cookies so fully expired persistent-cookie files are not treated as configured.
- Completed explicit `Retry without cookies` recovery for eligible failed public-media jobs. The per-job authentication policy is durable across process/service restart, ordinary Retry restores configured cookies, account/age/members-only/direct/cancelled jobs are excluded, and stale policy state is cleaned with job/history removal.
- End-to-end Compose coverage for the guarded no-cookie action is green: Android CI `33651634112` passed after fixing deterministic setup and scrolling to the actual failed-job card before asserting.

## In progress
- Establish a defensible app-startup baseline before changing startup behavior. Android CI `33670471626` still passed the full verify job but failed instrumentation before a usable `app_home_ms` artifact was retained. The rendered-home probe now runs first, and commits `bf49d52c` / `29383db2` remove the fragile logcat handoff: the test writes `app_home_ms` into the debug app's files directory, CI reads it back with `run-as`, validates the exact numeric format, and uploads instrumentation/startup reports under a stable run-specific artifact name even when a later instrumentation stage fails. No production startup optimization has been made or claimed yet.

## Validation / performance evidence
- Engine baseline `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Post-prewarm proof `33510436391`: `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1978`.
- Local extraction `33528825430`: `process_launch_ms=1946`, `local_extract_ms=2225`, `local_extract_overhead_ms=279`; the wrapper subprocess baseline dominates this deterministic path.
- Storage probe `33546570973`: 64 MiB write `26 ms`, final `fsync` `71 ms`.
- Transfer probe `33563442686`: 64 MiB localhost fresh transfer `364 ms`; 32 MiB HTTP Range resume `160 ms`. This does not justify changing the 256 KiB copy buffer or worker count.
- QuickJS wiring `33573354344`, cookie expiry `33585060641`, cookie-isolation eligibility `33601355781`, persisted auth policy `33606011186`, execution wiring `33611780875`, explicit requeue `33622415323`, tightened exclusions `33627146686`, production UI `33633640767`, and final end-to-end UI run `33651634112` all passed their relevant Android CI validation.
- Startup harness attempts `33663898765` and `33670471626` did not yield a defensible launch-to-home baseline; do not treat either as performance evidence.

## Known risks / review points
- Hosted-emulator timings guide optimization but are not representative ARM-device performance claims; confirm material gains on representative hardware before advertising speedups.
- yt-dlp process launch is structurally expensive under youtubedl-android because each execute call starts a fresh packaged-Python subprocess. Do not add dummy warm processes or migrate runtimes without device evidence and a compatibility plan.
- A successful FULL analysis can trigger one-time FFmpeg/aria2 prewarm even if the user never downloads; scope is intentionally limited to the strongest existing download-intent signal.
- YouTube challenge/auth behavior continues to evolve. Normal jobs use configured cookies; no-cookie retry remains explicit and user-driven and never silently changes selected quality.
- DASH/HLS intentionally do not use aria2c; ordinary HTTP transfers do.
- `main` remains intentionally user-controlled and untouched by autonomous maintenance.

## Weekly review
Compare `agent-dev` against `main`, inspect the latest Android CI for the newest Android code commit, review this file, and merge only the changes you want.
