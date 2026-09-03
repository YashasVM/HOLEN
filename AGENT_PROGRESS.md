# Agent progress

## Completed since weekly review
- All autonomous Android work remains on `agent-dev`; `main` is untouched.
- Hardened direct downloads with conservative resume validation, bounded transient retries, and short valid `Retry-After` handling for HTTP 429.
- Improved yt-dlp failure classification for auth/access, rate limits, unavailable/region-restricted media, requested-format failures, post-processing failures, transient transport failures, and incomplete-fragment/empty-output failures.
- Kept ordinary HTTP transfers on aria2c while routing DASH/HLS through yt-dlp native fragment downloading; youtubedl-android 0.18.1 QuickJS wiring is guarded by CI.
- Added durable explicit `Retry without cookies` recovery for eligible failed public-media jobs, with restart persistence, exclusions, cleanup, and end-to-end Compose coverage.
- Removed FFmpeg from metadata-only initialization and limited download-tool prewarm to successful FULL analysis.
- Added and validated startup, storage, transfer, post-processing, transport-error, fragment-error, fragment-integrity, and transient-fragment retry instrumentation coverage.
- Fragment integrity uses `--abort-on-unavailable-fragments`; Android CI `33736269904` and packaged-runtime test `33741738053` confirmed persistent missing fragments fail without publishing incomplete media.
- Android CI `33769714854` proved the packaged runtime can recover from two transient HTTP 503 fragment responses when the configured transfer retry budget is available.
- The later abort-safe probe `33775511969` unexpectedly completed the transient 503 recovery inside the first yt-dlp process while keeping `--abort-on-unavailable-fragments`; its only failure was the test's incorrect expectation of three whole-process launches. This rules out adding redundant outer yt-dlp retries for this case.

## In progress
- Validate the corrected production-equivalent probe in `daa1f618`: keep `--abort-on-unavailable-fragments`, `--retries 3`, and `--fragment-retries 3`, require exactly three fragment HTTP requests after two simulated 503s, and require complete finalized output from a single yt-dlp process.
- Do not add whole-process retry logic unless a representative failure is shown to escape yt-dlp's existing retry layers; process launch is expensive and extra retries would increase latency and duplicate work.
- Do not remove the integrity flag or adopt normal skip-unavailable behavior unless an independent completeness guarantee exists.
- Keep the current retry counts unless representative failures justify changing them; extra retries/backoff can increase failure latency, bandwidth use, and rate-limit pressure.

## Validation / performance evidence
- Startup probe `33705752404`: lint/tests/build/release assembly/16-KB checks passed; instrumentation passed twice with launch-to-rendered-home at `2293 ms` and `3312 ms`. Hosted-emulator variance is too high for small optimization claims.
- Engine baseline `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Transfer probe `33563442686`: 64 MiB localhost fresh transfer `364 ms`; 32 MiB Range resume `160 ms`; no evidence justified changing the 256 KiB copy buffer or worker count.
- Post-processing classification `33713261388`, transport classification `33720955973`, fragment-failure classification `33730714220`, fragment-integrity policy `33736269904`, missing-fragment packaged-runtime test `33741738053`, abort-priority regression `33752339316`, and corrected transient-fragment retry probe `33769714854` passed their relevant Android CI validation.
- Earlier retry probes `33746993258`, `33758294917`, and `33764121986` exposed test-fixture/configuration mistakes and remain negative test evidence rather than product regressions.
- `33775511969` passed normal Android verify/build/16-KB checks. Its instrumentation failure was an assertion-design error: yt-dlp recovered the two simulated HTTP 503 fragment responses within the first process instead of needing the proposed outer recovery loop.

## Known risks / weekly review
- Persistent unavailable fragments still correctly fail under `--abort-on-unavailable-fragments`; the current corrected probe must confirm transient HTTP 5xx recovery remains compatible with that integrity policy under production-equivalent retry settings.
- yt-dlp's normal skip-unavailable path can finalize incomplete media after retries are exhausted; HOLEN must not adopt it without independent integrity enforcement. Upstream issue #6793 documents that skipped fragments can be merged into incomplete output and make later completion require a full redownload.
- yt-dlp process launch is structurally expensive under youtubedl-android; avoid unconditional restarts or dummy warm processes.
- YouTube challenge/auth behavior continues to evolve; configured cookies remain the normal path and no-cookie retry stays explicit and user-driven.
- Compare `agent-dev` against `main`, inspect the latest Android CI for the newest Android code commit, and merge only the changes you want.
