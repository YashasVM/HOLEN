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
- Enabled the official yt-dlp `ejs:github` remote component for full YouTube analysis and YouTube downloads, with an explicit reusable Android cache. Quick shared-link analysis and non-YouTube extractors remain unchanged. Android CI `34158081428` and `34158104192`, plus generic CI `34158104213`, passed for the production policy and host-coverage tests.
- Strengthened the opt-in Android EJS compatibility probe so a usable-network run must prove successful first extraction, non-empty cache creation, successful second extraction, and retained cache state. Android CI `34161519848` passed verify/instrumentation for this test change; the live EJS probe itself remained intentionally skipped in normal hosted CI.
- Fixed the strict EJS probe report parser so it consumes the new cache file/byte counters. Android CI `34164996314` and generic CI `34164996308` passed for the parser change.
- Corrected direct-file HTTP 402 failure classification: arbitrary HTTPS file servers now report payment/additional-access requirements instead of a rate limit, while yt-dlp extractor HTTP 402 remains treated as an overuse block. Generic CI `34171668938` and Android CI `34171668951` both passed the focused regression coverage.
- Corrected a missing-output failure path where `IOException("The media engine completed without an output file.")` was incorrectly falling through to the generic network-error message. The Android UI now tells the user the engine produced no usable output and suggests re-analysis plus engine update/reset if it repeats. Generic CI `34181614787` and Android CI `34181614839` both passed the regression coverage.

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

The deterministic policy, build, instrumentation, and strict-probe parsing paths are green. Live EJS extraction remains deliberately unclaimed because hosted GitHub runner traffic has been rate-limited by YouTube. No further production fallback is justified without a non-rate-limited Android/network run that can distinguish solver compatibility from network blocking.

While that external validation remains blocked, focused Android failure/retry audits continue. The direct HTTP 402 and missing-engine-output classification fixes are both fully green; no additional production change is justified from the currently inspected paths without a concrete failure case.

## Validation / reviewer state

- Android CI `34164996314`: passed for the strict EJS parser fix.
- Generic CI `34164996308`: passed for the same parser fix.
- Generic CI `34171668938`: passed for the direct-file HTTP 402 classification regression coverage.
- Android CI `34171668951`: passed for the same focused change.
- Generic CI `34171718964`: passed for the progress-state update following that change.
- Generic CI `34181614787`: passed for the missing-engine-output regression test.
- Android CI `34181614839`: passed for the same focused change.
- Normal hosted instrumentation intentionally leaves the live EJS probe disabled unless `HOLEN_EJS_REMOTE_PROBE` is explicitly enabled.
- No open PRs or issues are present. No actionable CodeRabbit or `Yashas's code review bot:` feedback is pending.

## Known risks / review points

- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; later runs should reuse yt-dlp's explicit cache unless Android evicts it.
- Live YouTube EJS behavior still needs one successful opt-in run on a network not blocked/rate-limited by YouTube before treating challenge compatibility as fully proven.
- Retaining cache artifacts across the second probe proves persistent cache state survives reuse; it does not by itself prove yt-dlp made zero network requests on the second extraction.
- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Explicit user cancellation deliberately deletes staging and therefore is not pause/resume; crash/service interruption recovery is separate and proven to preserve resumable state.
- Current yt-dlp's `Aria2cFD` already uses 16 connections/splits and appends fixed flags including `--always-resume=false`; do not add duplicate connection tuning or assume earlier duplicate flags win.

## Highest-value next step

Run the strict EJS first-fetch/cache/reuse probe on a non-rate-limited Android connection when one is available. Until then, continue focused Android reliability/performance audits and only modify production code when a concrete defect, lost-resume case, or measurable bottleneck is demonstrated.