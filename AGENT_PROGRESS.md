# Agent progress

## Completed since weekly review
- All autonomous Android work remains on `agent-dev`; `main` is untouched.
- Hardened direct downloads with conservative resume validation, bounded transient retries, and short valid `Retry-After` handling for HTTP 429.
- Improved yt-dlp failure classification for auth/access, rate limits, unavailable/region-restricted media, requested-format failures, post-processing failures, transient transport failures, and incomplete-fragment/empty-output failures.
- Kept ordinary HTTP transfers on aria2c while routing DASH/HLS through yt-dlp native fragment downloading; youtubedl-android 0.18.1 QuickJS wiring is guarded by CI.
- Added durable explicit `Retry without cookies` recovery for eligible failed public-media jobs, with restart persistence, exclusions, cleanup, and end-to-end Compose coverage.
- Removed FFmpeg from metadata-only initialization and limited download-tool prewarm to successful FULL analysis.
- Added and validated startup, storage, transfer, post-processing, transport-error, fragment-error, fragment-integrity, and transient-fragment retry instrumentation coverage.
- Fragment integrity uses `--abort-on-unavailable-fragments`; packaged-runtime tests prove transient HTTP 503 fragment failures recover inside the existing retry budget, while persistent 503 failures exhaust the bounded retries, fail cleanly, and do not publish incomplete media.
- Closed the fragment retry/integrity investigation without increasing retry counts or adding redundant outer yt-dlp process restarts.
- Prevented failed automatic/manual yt-dlp update checks from clearing HOLEN's otherwise working media runtime. Upstream youtubedl-android checks the network before replacing the binary and restores its bundled binary on install failure, so HOLEN's extra destructive reset only converted ordinary update failures into forced restarts.

## In progress
- Validate `fix(android): preserve engine after update failure` through Android CI, including lint/tests/release assembly/16-KB checks.
- Evaluate extractor freshness/update cadence separately after update failure is safe; do not shorten the current seven-day check simply because yt-dlp releases frequently.
- Do not add whole-process retry logic unless a representative failure is shown to escape yt-dlp's existing retry layers; process launch is expensive and extra retries would increase latency and duplicate work.
- Keep the current retry counts unless representative failures justify changing them; extra retries/backoff can increase failure latency, bandwidth use, and rate-limit pressure.

## Validation / performance evidence
- Startup probe `33705752404`: lint/tests/build/release assembly/16-KB checks passed; instrumentation passed twice with launch-to-rendered-home at `2293 ms` and `3312 ms`. Hosted-emulator variance is too high for small optimization claims.
- Engine baseline `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Transfer probe `33563442686`: 64 MiB localhost fresh transfer `364 ms`; 32 MiB Range resume `160 ms`; no evidence justified changing the 256 KiB copy buffer or worker count.
- Post-processing classification `33713261388`, transport classification `33720955973`, fragment-failure classification `33730714220`, fragment-integrity policy `33736269904`, missing-fragment packaged-runtime test `33741738053`, abort-priority regression `33752339316`, corrected transient-fragment retry probe `33769714854`, abort-safe transient retry probe `33781602289`, and persistent-fragment retry exhaustion `33787210401` passed their relevant Android CI validation.
- Earlier retry probes `33746993258`, `33758294917`, `33764121986`, and `33775511969` exposed test-fixture/expectation mistakes and remain useful negative test evidence rather than product regressions.

## Known risks / weekly review
- The engine-update preservation fix is not considered validated until Android CI for `1dfd23e6` completes.
- yt-dlp extractor compatibility changes frequently; HOLEN updates the embedded yt-dlp runtime separately from the Android wrapper. The current seven-day automatic check may leave a broken extractor stale, but more frequent checks also add network/maintenance churn.
- yt-dlp process launch is structurally expensive under youtubedl-android; avoid unconditional restarts or dummy warm processes.
- YouTube challenge/auth behavior continues to evolve; configured cookies remain the normal path and no-cookie retry stays explicit and user-driven.
- Compare `agent-dev` against `main`, inspect the latest Android CI for the newest Android code commit, and merge only the changes you want.
