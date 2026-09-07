# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline before this update: `main` `4b46036d`; `agent-dev` was 106 commits ahead and 0 behind.

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

The first Android EJS probe exposed two important integration facts before any production flag was enabled:

1. youtubedl-android automatically appends `--no-cache-dir` unless the request explicitly supplies `--cache-dir`; naive production `ejs:github` use would therefore refetch remote components on each invocation.
2. youtubedl-android throws `YoutubeDLException` for non-zero yt-dlp exits, using yt-dlp stderr as the exception message. Transient YouTube/GitHub failures therefore need deliberate diagnostic capture rather than being treated as a product regression.

Commit `48958dab` corrected the probe by using a dedicated explicit cache and recording extraction exceptions. Corrected Android CI `34074610213` passed completely, including instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification.

The corrected artifact did not validate cache reuse: both EJS attempts exited through the wrapper exception path (`exit=-1`), with first attempt 8722 ms and second attempt 7416 ms. Both reported remote-component signals and neither reported a cache-hit signal. The repeated yt-dlp process probe in the same run measured 1837 ms first launch vs 951 ms repeated launch, so most of the 7–9 second EJS probe time was not ordinary process startup alone.

Commit `9a4ea84a` preserved bounded first/cached EJS failure diagnostics, and commit `519d7e7d` surfaced them in the workflow summary. Android CI `34081234406` identified the actual blocker: both attempts were rejected by YouTube with HTTP 429 (`Too Many Requests`) before meaningful EJS/cache behavior could be established. Commit `a59fd619` then classified HTTP 429 and skipped the redundant cached-reuse attempt when the first request was already upstream-blocked.

Commit `cac53d9d` accidentally replaced unrelated, already-working instrumentation-runner logic with a stale variant while adding EJS summary fields. Commit `deb8b64b` restored the established runner, and Android CI `34088583867` passed completely, closing that regression.

Hosted GitHub runners are not a defensible place to keep probing live YouTube EJS behavior: the runner IP is being rate-limited, so the test consumes time without validating remote-component acquisition or cache reuse. Commit `9c78e049` therefore stops running the live EJS probe by default in Android CI. The probe remains available behind `HOLEN_EJS_REMOTE_PROBE=true` for an environment where YouTube access is suitable for compatibility validation.

Production analysis/download behavior remains unchanged: no request enables `ejs:github`, and Android version metadata was not changed.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- Corrected EJS probe Android CI `34074610213` passed completely.
- EJS diagnostic-preservation Android CI `34078013194` passed completely.
- EJS surfaced-diagnostics Android CI `34081234406` passed completely and identified upstream YouTube HTTP 429 as the probe blocker.
- Restored instrumentation runner Android CI `34088583867` passed completely.
- Commit `9c78e049` now awaits Android CI validation with the unreliable live EJS probe skipped by default.
- No open PRs or issues were present at the start of this run.
- Recent PR #19 has no submitted reviews; no actionable CodeRabbit or `Yashas's code review bot:` feedback was found.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministically end-to-end validated.
- Emulator timing is useful for bottleneck ranking, not a claim of phone-level absolute latency or promised speedup.
- Reusing analysis output for download is currently rejected because signed media URLs, cookies/auth state, format selection, and extractor behavior can change between analysis and download.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent.
- Enabling `ejs:github` without an explicit cache strategy would add repeated remote-component network cost because youtubedl-android disables yt-dlp caching by default.
- A successful first EJS acquisition plus cached reuse still has not been demonstrated on Android. Keep production EJS remote-components disabled until this is validated in a network environment that is not blocked by YouTube.

## Highest-value next step

Validate commit `9c78e049` in Android CI. If green, treat the hosted-runner EJS investigation as intentionally blocked rather than repeatedly retrying it, then return to the highest-value Android reliability gap: deterministic restart/resume validation across the real yt-dlp/aria2 download path.
