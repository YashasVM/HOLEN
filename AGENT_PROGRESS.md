# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current baseline inspected before this update: `main` `4b46036d`; `agent-dev` is 98 commits ahead and 0 behind.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting so numbered yt-dlp `.part-FragN` artifacts are not counted as completed progress.
- Prevented numbered yt-dlp fragment temp files from being selected as completed media while preserving legitimate filenames containing `part-Frag` text.
- Direct HTTP downloads now honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Added and validated repeated yt-dlp process-launch timing; Android CI `34059630027` passed instrumentation, lint/unit/build, APK assembly, and 16 KB native-library verification.

## Current work: YouTube EJS compatibility

Recent successful Android CI artifacts show yt-dlp process launch as the dominant first-analysis cost (~1.85–2.35 s), while local extractor overhead, storage writes/fsync, and loopback transfer timings are materially smaller. The evidence does not support speculative transfer-buffer, fsync, SAF-copy, or fragment-concurrency tuning.

The proposed analyze-to-download reuse path remains rejected because reused extraction output can preserve expiring/signed direct media URLs and stale auth/cookie-dependent state, and upstream has an open format-selection issue in the two-stage `--load-info-json` pattern.

The EJS investigation established that youtubedl-android `0.18.1` injects QuickJS but does not bundle `yt-dlp-ejs` or enable `--remote-components`. Current yt-dlp therefore needs another source for matching EJS solver scripts when YouTube requires JavaScript challenge solving.

The first Android EJS probe exposed two important harness facts before any production flag was enabled:

1. Android CI `34071365877` failed only in the instrumentation job; lint/unit/build, release APK assembly, and 16 KB native-library verification all passed.
2. youtubedl-android automatically appends `--no-cache-dir` unless the request explicitly supplies `--cache-dir`. That made the original first-fetch-vs-cached-reuse probe invalid and would also make naive production `ejs:github` usage refetch remote components on each yt-dlp invocation.
3. youtubedl-android throws `YoutubeDLException` for non-zero yt-dlp exits, so transient YouTube/GitHub failures can abort an instrumentation test unless the measurement harness records the exception deliberately.

Commit `48958dab` fixes the probe rather than production behavior: it gives the EJS test a dedicated explicit yt-dlp cache directory, clears that directory before the first request, reuses it for the second request, and records external extraction exceptions as probe diagnostics instead of failing the entire instrumentation run. Interrupted execution is still propagated correctly.

No production analysis/download request currently enables `ejs:github` and no Android version metadata was changed.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- Generic CI `34071385886` for the previous EJS progress tip passed.
- Initial EJS probe Android CI `34071365877` failed in instrumentation while the verify job passed; this failure led to the cache/exception-handling correction above.
- Corrected EJS probe Android CI `34074610213` and generic CI `34074610173` are currently running.
- No open PRs or issues were present at the start of this run.
- PR #19 has no submitted reviews; there is no actionable CodeRabbit or `Yashas's code review bot:` feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministically end-to-end validated.
- Emulator timing is useful for bottleneck ranking, not a claim of phone-level absolute latency or promised speedup.
- Reusing analysis output for download is currently rejected because signed media URLs, cookies/auth state, format selection, and extractor behavior can change between analysis and download.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent.
- Enabling `ejs:github` without an explicit cache strategy would add repeated remote-component network cost because youtubedl-android disables yt-dlp caching by default.
- The EJS probe depends on external YouTube/GitHub availability for meaningful compatibility evidence; external failures are now recorded rather than hidden.

## Highest-value next step

Finish corrected Android CI `34074610213` and inspect its EJS first-fetch versus cached-reuse report. If explicit caching works reliably, add a controlled remote-unavailable test that proves cached EJS remains usable without GitHub before considering any production `ejs:github` integration. If cached reuse is unreliable or expensive, keep production unchanged and investigate version-coupled local EJS packaging instead.
