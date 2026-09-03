# Agent progress

## Completed since weekly review
- All autonomous Android work remains on `agent-dev`; `main` is untouched.
- Hardened direct downloads with conservative resume validation, bounded transient retries, and short valid `Retry-After` handling for HTTP 429.
- Improved yt-dlp failure classification for auth/access, rate limits, unavailable/region-restricted media, requested-format failures, post-processing failures, transient transport failures, and incomplete-fragment/empty-output failures.
- Kept ordinary HTTP transfers on aria2c while routing DASH/HLS through yt-dlp native fragment downloading; youtubedl-android 0.18.1 QuickJS wiring is guarded by CI.
- Added durable explicit `Retry without cookies` recovery for eligible failed public-media jobs, with restart persistence, exclusions, cleanup, and end-to-end Compose coverage.
- Removed FFmpeg from metadata-only initialization and limited download-tool prewarm to successful FULL analysis.
- Added and validated startup, storage, transfer, post-processing, transport-error, fragment-error, and fragment-integrity instrumentation coverage.
- Fragment integrity currently uses `--abort-on-unavailable-fragments`; Android CI `33736269904` and packaged-runtime test `33741738053` confirmed missing fragments fail without publishing incomplete media.
- Android CI `33752339316` confirmed that the integrity flag aborts on the first transient HTTP 503 before yt-dlp can consume the configured retry budget.

## In progress
- Resolve the retry/integrity conflict for fragmented downloads without ever publishing known-incomplete media.
- Probe `7036735b` correctly restored production `--retries 3` and `--fragment-retries 3`, but Android CI `33764121986` failed for a test-fixture reason rather than retry behavior: after the synthetic fragment path recovered, the probe reached metadata handling without the FFmpeg runtime initialized and failed with `ffprobe not found` / `expected str, bytes or os.PathLike object, not NoneType`.
- Commit `b683447e` initializes `FFmpeg` in the retry probe, matching production download startup (`ensureInitialized(needsFfmpeg = true, needsAria2c = true)`). Android CI `33769714854` is validating the corrected production-equivalent probe.
- If that probe proves two HTTP 503 responses recover on the third fragment request, move integrity enforcement after the bounded retry budget: permit retries, detect any ultimately skipped fragment, and refuse/remove incomplete output before HOLEN publishes it.
- Keep the current retry counts unless representative failures justify changing them; extra retries/backoff can increase failure latency, bandwidth use, and rate-limit pressure.

## Validation / performance evidence
- Startup probe `33705752404`: lint/tests/build/release assembly/16-KB checks passed; instrumentation passed twice with launch-to-rendered-home at `2293 ms` and `3312 ms`. Hosted-emulator variance is too high for small optimization claims.
- Engine baseline `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Transfer probe `33563442686`: 64 MiB localhost fresh transfer `364 ms`; 32 MiB Range resume `160 ms`; no evidence justified changing the 256 KiB copy buffer or worker count.
- Post-processing classification `33713261388`, transport classification `33720955973`, fragment-failure classification `33730714220`, fragment-integrity policy `33736269904`, missing-fragment packaged-runtime test `33741738053`, and abort-priority regression `33752339316` all passed their relevant Android CI validation.
- Retry probe `33746993258` proved `--abort-on-unavailable-fragments` prevents transient-fragment recovery. Probe `33758294917` was invalid for production because it forced `--retries 0`. Probe `33764121986` restored production retry counts but failed because the successful synthetic stream reached FFprobe without production-equivalent FFmpeg initialization; this is corrected in `b683447e`.

## Known risks / weekly review
- Production `--abort-on-unavailable-fragments` still favors integrity over recovery and can fail a fragmented download on one transient unavailable-fragment response.
- yt-dlp's normal skip-unavailable path can finalize incomplete media after retries are exhausted; HOLEN must not adopt it without independent integrity enforcement.
- yt-dlp process launch is structurally expensive under youtubedl-android; do not add dummy warm processes or migrate runtimes without representative-device evidence.
- YouTube challenge/auth behavior continues to evolve; configured cookies remain the normal path and no-cookie retry stays explicit and user-driven.
- Compare `agent-dev` against `main`, inspect the latest Android CI for the newest Android code commit, and merge only the changes you want.
