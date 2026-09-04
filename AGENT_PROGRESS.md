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
- Prevented failed automatic/manual yt-dlp update checks from clearing HOLEN's otherwise working media runtime; Android CI `33793480906` passed for that production fix.
- Failed yt-dlp update checks now retry after 24 hours instead of being suppressed for the normal seven-day successful-check interval; Android CI `33799324069` and generic CI passed.
- Moved due yt-dlp network refreshes off the foreground startup path. `warmup()` now performs local Python/yt-dlp initialization only; automatic stable refresh is best-effort after genuine backgrounding and is skipped during configuration changes, active downloads, and Activity teardown.
- Android CI `33831320310` passed after the teardown guard, including lint/tests/build, release APK assembly, 16 KB native-library verification, and the full instrumentation job. The earlier `33827812724` instrumentation failure was a lifecycle-hook regression caught before the task was closed.

## In progress
- Validate a narrowly classified stale-extractor recovery path before adding any automatic update-and-retry behavior. Auth, DRM, rate-limit, storage, cancellation, and ordinary network failures must never trigger an extractor-update retry.
- Do not add whole-process retry logic unless a representative failure is shown to escape yt-dlp's existing retry layers; process launch is expensive and extra retries would increase latency and duplicate work.
- Keep the current retry counts unless representative failures justify changing them; extra retries/backoff can increase failure latency, bandwidth use, and rate-limit pressure.

## Validation / performance evidence
- Startup probe `33705752404`: lint/tests/build/release assembly/16-KB checks passed; instrumentation passed twice with launch-to-rendered-home at `2293 ms` and `3312 ms`. Hosted-emulator variance is too high for small optimization claims.
- Engine baseline `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Transfer probe `33563442686`: 64 MiB localhost fresh transfer `364 ms`; 32 MiB Range resume `160 ms`; no evidence justified changing the 256 KiB copy buffer or worker count.
- Post-processing classification `33713261388`, transport classification `33720955973`, fragment-failure classification `33730714220`, fragment-integrity policy `33736269904`, missing-fragment packaged-runtime test `33741738053`, abort-priority regression `33752339316`, corrected transient-fragment retry probe `33769714854`, abort-safe transient retry probe `33781602289`, persistent-fragment retry exhaustion `33787210401`, engine-update preservation `33793480906`, failed-update retry cadence `33799324069`, and background-refresh lifecycle correction `33831320310` passed their relevant Android CI validation.
- Earlier retry probes `33746993258`, `33758294917`, `33764121986`, `33775511969`, and background-refresh probe `33827812724` exposed test-fixture/expectation/lifecycle mistakes and remain useful negative evidence rather than hidden failures.

## Known risks / weekly review
- yt-dlp extractor compatibility changes frequently. HOLEN remains on youtubedl-android `0.18.1`; current extractors still depend on its supported runtime updater rather than dependency churn.
- Successful automatic engine checks remain weekly; failed checks use the validated 24-hour retry cadence. Automatic updates are intentionally best-effort after backgrounding so they cannot block first metadata interaction.
- Android may kill a backgrounded process before a best-effort update finishes. The existing active engine is kept on update failure, and the next eligible background transition retries according to the saved cadence.
- yt-dlp process launch is structurally expensive under youtubedl-android; avoid unconditional restarts or dummy warm processes.
- YouTube challenge/auth behavior continues to evolve; configured cookies remain the normal path and no-cookie retry stays explicit and user-driven.
- Compare `agent-dev` against `main`, inspect the latest Android CI for the newest Android code commit, and merge only the changes you want.
