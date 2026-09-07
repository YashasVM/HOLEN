# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` is ahead with no drift from `main` detected in this run.

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
- Enabled the official yt-dlp `ejs:github` remote component for full YouTube analysis and YouTube downloads, with an explicit reusable Android cache. Quick shared-link analysis and non-YouTube extractors remain unchanged. Android CI `34158081428` and `34158104192`, plus generic CI `34158104213`, passed for the production policy and host-coverage tests.
- Strengthened the opt-in Android EJS compatibility probe so a usable-network run must prove successful first extraction, non-empty cache creation, successful second extraction, and retained cache state. Android CI `34161519848` passed verify/instrumentation for this test change; the live EJS probe itself remained intentionally skipped in normal hosted CI.

## Performance evidence

Android CI `34161519848` provides an emulator baseline rather than a claimed phone benchmark:

- App home: `3683 ms`.
- Cold yt-dlp runtime initialization: `1454 ms`; FFmpeg extraction/init: `1684 ms`; aria2c: `176 ms`.
- Re-entering FFmpeg + aria2c after prewarm: `0 ms`, supporting the existing post-analysis tool-prewarm strategy.
- yt-dlp process launch: `2614 ms`; back-to-back launch probe: `1955 ms` then `979 ms`.
- Local extractor total: `2253 ms`; measured local extractor overhead was `0 ms` relative to the process-launch baseline used by the probe.
- 64 MiB private-storage write: `24 ms` plus `54 ms` fsync; 64 MiB loopback transfer: `284 ms`; 32 MiB resumed remainder: `135 ms`.

These numbers rank emulator-side bottlenecks only. They do not claim absolute phone latency or a measured production speedup.

## Current work: live YouTube EJS validation

Upstream yt-dlp's current EJS guidance requires a supported JavaScript runtime plus solver scripts for YouTube challenge solving. HOLEN already exposes bundled QuickJS, and production now enables the official `ejs:github` distribution with an explicit cache for full YouTube operations.

The deterministic policy/build path is green. Commit `839e5907` tightened the opt-in live probe, but review of the CI harness found that its report parser still expected the old field layout and would reject the newly added cache file/byte counters. Commit `f3c7fb01` updates the parser and its checks to consume those counters, so a future opt-in run can report the strict probe result instead of failing during summary extraction. Production download behavior is unchanged.

## Validation / reviewer state

- Android CI `34161519848`: passed both verify and instrumentation for the stricter EJS probe code. The live EJS test was skipped by design because `HOLEN_EJS_REMOTE_PROBE` was not enabled.
- The opt-in harness now validates and reports `first_cache_files`, `first_cache_bytes`, `cached_cache_files`, and `cached_cache_bytes` in addition to exit/status fields.
- Fresh CI is pending for the parser-only `f3c7fb01` change.
- The live EJS probe remains opt-in because hosted GitHub runners have returned YouTube HTTP 429; no live compatibility claim is made from a blocked environment.
- No open PRs or issues are present. No actionable CodeRabbit or `Yashas's code review bot:` feedback is pending.

## Known risks / review points

- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; later runs should reuse yt-dlp's explicit cache unless Android evicts it.
- Live YouTube EJS behavior still needs one successful opt-in run on a network not blocked/rate-limited by YouTube before treating challenge compatibility as fully proven.
- Retaining cache artifacts across the second probe proves persistent cache state survives reuse; it does not by itself prove yt-dlp made zero network requests on the second extraction.
- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Explicit user cancellation deliberately deletes staging and therefore is not pause/resume; crash/service interruption recovery is separate and proven to preserve resumable state.
- Current yt-dlp's `Aria2cFD` already uses 16 connections/splits and appends fixed flags including `--always-resume=false`; do not add duplicate connection tuning or assume earlier duplicate flags win.

## Highest-value next step

Validate CI for `f3c7fb01`. Once green, run the opt-in EJS probe on a non-rate-limited Android/network environment; if that succeeds, close the EJS task. If it fails, use its exact first-fetch/cache diagnostics to fix the real compatibility problem rather than adding speculative fallback behavior.
