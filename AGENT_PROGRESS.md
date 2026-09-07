# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` remains ahead with no drift from `main` detected in this run.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting and completed-output selection so numbered yt-dlp `.part-FragN` artifacts are not mistaken for completed media.
- Direct HTTP downloads honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Rejected analyze-to-download extraction reuse because signed URLs, auth/cookie state, format selection, and extractor state can become stale between operations.
- Completed deterministic low-level yt-dlp/aria2 restart-resume validation. Android CI `34135963372` passed both verify and instrumentation: retained partial media plus `.aria2` state, killed first process, fresh process issued a non-zero completed-piece `Range`, and final media was byte-identical.
- Validated HOLEN's store/output recovery boundary in Android CI `34141010546`. Interrupted media jobs retain partial media plus `.aria2` state through orphan cleanup and `requeueInterrupted()`.
- Closed service-level restart recovery in Android CI `34153823593`: both instrumentation and verify passed, including lint/tests/build, release APK assembly, 16 KB native-library verification, and the real `DownloadService` reclaim/staging-preservation probe.

## Performance evidence

Android CI `34153823593` provides a fresh emulator baseline rather than a claimed phone benchmark:

- App home: `3504 ms`.
- Cold yt-dlp runtime initialization: `1471 ms`; FFmpeg extraction/init: `1504 ms`; aria2c: `136 ms`.
- Re-entering FFmpeg + aria2c after prewarm: `0 ms`, supporting the existing post-analysis tool-prewarm strategy.
- yt-dlp process launch: `1872 ms`; back-to-back launch probe: `1856 ms` then `1564 ms`.
- Local extractor total: `2124 ms`, with only `252 ms` above the process-launch baseline.
- 64 MiB private-storage write: `30 ms` plus `74 ms` fsync; 64 MiB loopback transfer: `264 ms`; 32 MiB resumed remainder: `107 ms`.

These numbers rank emulator-side bottlenecks only. They do not claim absolute phone latency or a measured production speedup.

## Current work: current YouTube EJS compatibility

Upstream yt-dlp's July 2026 EJS guidance says YouTube challenge solving now needs both a supported JavaScript runtime and the matching EJS solver scripts. HOLEN's Android runtime already exposes bundled QuickJS, but youtubedl-android `0.18.1` disables yt-dlp caching unless the caller supplies `--cache-dir`; enabling `ejs:github` without an explicit cache would therefore create needless repeated acquisition traffic.

Commit `396909c6` enables yt-dlp's official `ejs:github` remote component only for full YouTube analysis and YouTube downloads, with an explicit reusable app-cache directory. Quick shared-link analysis stays on the current lightweight path, and non-YouTube extractors receive no remote-component option. Commit `b8a1e7fb` adds a unit policy test covering YouTube/youtu.be/youtube-nocookie hosts and confirming unrelated hosts remain untouched.

## Validation / reviewer state

- Android CI `34153823593`: instrumentation and verify passed for the completed service-recovery work.
- Fresh Android CI `34158104192` and generic CI `34158104213` are queued/running for the EJS production policy and unit test.
- The existing live EJS probe remains opt-in because hosted GitHub runners have returned YouTube HTTP 429; no live compatibility claim is made from that environment.
- No open PRs or issues are present. No actionable CodeRabbit or `Yashas's code review bot:` feedback is pending.

## Known risks / review points

- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; later runs should reuse yt-dlp's explicit cache unless Android evicts it.
- Live YouTube EJS behavior still needs validation on a network not blocked/rate-limited by YouTube before treating this as fully proven compatibility, even if deterministic build/unit/instrumentation CI is green.
- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Explicit user cancellation deliberately deletes staging and therefore is not pause/resume; crash/service interruption recovery is separate and now proven to preserve resumable state.
- Current yt-dlp's `Aria2cFD` already uses 16 connections/splits and appends fixed flags including `--always-resume=false`; do not add duplicate connection tuning or assume earlier duplicate flags win.

## Highest-value next step

Inspect Android CI `34158104192` and generic CI `34158104213`. If green, validate first-fetch plus cached EJS reuse on a suitable non-rate-limited Android/network environment; if CI fails, fix the exact build/test regression before expanding the change.
