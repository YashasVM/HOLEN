# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current baseline inspected before this update: `main` `4b46036d`; `agent-dev` was 99 commits ahead and 0 behind before this run's diagnostic commit.

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

1. youtubedl-android automatically appends `--no-cache-dir` unless the request explicitly supplies `--cache-dir`; naive production `ejs:github` use would therefore refetch remote components on each invocation.
2. youtubedl-android throws `YoutubeDLException` for non-zero yt-dlp exits, so transient YouTube/GitHub failures need to be recorded deliberately in a compatibility probe rather than failing unrelated Android validation.

Commit `48958dab` corrected the probe by using a dedicated explicit cache and recording extraction exceptions. Corrected Android CI `34074610213` passed completely, including instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification.

The corrected artifact did not yet validate cache reuse: both EJS attempts exited through the wrapper exception path (`exit=-1`), with first attempt 8722 ms and second attempt 7416 ms. Both reported remote-component signals and neither reported a cache-hit signal. The repeated yt-dlp process probe in the same run measured 1837 ms first launch vs 951 ms repeated launch, so most of the 7–9 second EJS probe time is not ordinary process startup alone.

Because the wrapper exception reduced the stored diagnostics to a message and the CI artifact only preserved the summary line, the precise upstream failure reason was not recoverable from that run. Commit `9a4ea84a` now emits the bounded first/cached EJS diagnostics into logcat so the next Android artifact can identify whether the failure is YouTube extraction, GitHub remote-component retrieval, EJS compatibility, or another environment problem. This remains test-only; production EJS behavior is unchanged.

No production analysis/download request currently enables `ejs:github` and no Android version metadata was changed.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- Corrected EJS probe Android CI `34074610213` passed completely; generic CI `34074610173` and progress-tip CI `34074647090` also passed.
- Corrected EJS probe evidence: first 8722 ms / exit -1 / remote signal true / cache signal false; repeated 7416 ms / exit -1 / remote signal true / cache signal false.
- No open PRs or issues were present at the start of this run.
- PR #19 has no submitted reviews; there is no actionable CodeRabbit or `Yashas's code review bot:` feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministically end-to-end validated.
- Emulator timing is useful for bottleneck ranking, not a claim of phone-level absolute latency or promised speedup.
- Reusing analysis output for download is currently rejected because signed media URLs, cookies/auth state, format selection, and extractor behavior can change between analysis and download.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent.
- Enabling `ejs:github` without an explicit cache strategy would add repeated remote-component network cost because youtubedl-android disables yt-dlp caching by default.
- The corrected EJS run still did not establish a successful first fetch or cache reuse. Do not add a production remote-component flag until the upstream failure cause and offline/cached behavior are understood.

## Highest-value next step

Inspect the next Android EJS diagnostic artifact produced by `9a4ea84a`. If it shows a transient external-network or test-video failure, make the probe target/environment more deterministic and repeat it. If it shows an actual youtubedl-android/yt-dlp EJS incompatibility, keep production unchanged and evaluate a version-coupled local EJS packaging path instead of introducing a brittle runtime GitHub dependency. Only after a successful first fetch is demonstrated should a controlled remote-unavailable cached-reuse test be added.
