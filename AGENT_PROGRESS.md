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
- Android CI `33752339316` confirmed that the integrity flag aborts on the first transient HTTP 503 before yt-dlp can consume the configured fragment retry budget.
- Android CI `33769714854` passed the corrected production-equivalent retry probe after initializing FFmpeg, confirming the packaged runtime can recover from two HTTP 503 fragment responses when skip-unavailable behavior is allowed.

## In progress
- Resolve the retry/integrity conflict for fragmented downloads without ever publishing known-incomplete media.
- Commit `d8673a46` probes a simpler integrity-preserving strategy before changing production: keep `--abort-on-unavailable-fragments`, restart the failed yt-dlp process with `--continue` only for unavailable-fragment failures, and bound the whole-process attempts. The deterministic HLS fixture returns HTTP 503 twice then succeeds, so the probe requires two aborts followed by a complete finalized output on the third process attempt.
- Do not remove the integrity flag or adopt normal skip-unavailable behavior unless an independent completeness guarantee exists.
- Keep the current yt-dlp retry counts unless representative failures justify changing them; extra retries/backoff can increase failure latency, bandwidth use, and rate-limit pressure.

## Validation / performance evidence
- Startup probe `33705752404`: lint/tests/build/release assembly/16-KB checks passed; instrumentation passed twice with launch-to-rendered-home at `2293 ms` and `3312 ms`. Hosted-emulator variance is too high for small optimization claims.
- Engine baseline `33484712612`: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- Transfer probe `33563442686`: 64 MiB localhost fresh transfer `364 ms`; 32 MiB Range resume `160 ms`; no evidence justified changing the 256 KiB copy buffer or worker count.
- Post-processing classification `33713261388`, transport classification `33720955973`, fragment-failure classification `33730714220`, fragment-integrity policy `33736269904`, missing-fragment packaged-runtime test `33741738053`, abort-priority regression `33752339316`, and corrected transient-fragment retry probe `33769714854` passed their relevant Android CI validation.
- Earlier retry probes `33746993258`, `33758294917`, and `33764121986` exposed the abort/retry interaction and two test-fixture mistakes; they are retained as negative evidence rather than treated as product regressions.

## Known risks / weekly review
- Production `--abort-on-unavailable-fragments` still favors integrity over recovery and can fail a fragmented download on one transient unavailable-fragment response.
- yt-dlp's normal skip-unavailable path can finalize incomplete media after retries are exhausted; HOLEN must not adopt it without independent integrity enforcement. Upstream issue #6793 documents that skipped fragments can be merged into incomplete output and make later completion require a full redownload.
- Whole-process recovery is only acceptable if it remains narrowly classified, bounded, cancellation-aware, and does not retry auth/storage/permanent extractor failures.
- yt-dlp process launch is structurally expensive under youtubedl-android; do not add unconditional restarts or dummy warm processes.
- YouTube challenge/auth behavior continues to evolve; configured cookies remain the normal path and no-cookie retry stays explicit and user-driven.
- Compare `agent-dev` against `main`, inspect the latest Android CI for the newest Android code commit, and merge only the changes you want.
