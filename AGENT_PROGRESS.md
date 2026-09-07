# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` was 110 commits ahead and 0 behind before this run.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting and completed-output selection so numbered yt-dlp `.part-FragN` artifacts are not mistaken for completed media.
- Direct HTTP downloads honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Measured repeated yt-dlp process startup; Android CI showed the process launch itself is a material part of first-analysis latency, while local extractor/storage/loopback timings were smaller. No unmeasured throughput claim was made.
- Rejected analyze-to-download extraction reuse because signed URLs, auth/cookie state, format selection, and extractor state can become stale between the two operations.
- Investigated current YouTube EJS support. youtubedl-android `0.18.1` supplies QuickJS but not matching EJS solver scripts, while naive `ejs:github` use would repeatedly fetch because the wrapper disables yt-dlp caching unless an explicit cache directory is supplied.
- Hosted GitHub runners cannot defensibly validate live YouTube EJS behavior because YouTube returns HTTP 429. The live EJS probe is therefore opt-in behind `HOLEN_EJS_REMOTE_PROBE=true`; production does not enable `ejs:github`. Android CI `34093307496` passed with the live probe gated off.

## Current work: deterministic yt-dlp / aria2 restart-resume validation

Production Android downloads already pass yt-dlp `--continue` and use `libaria2c.so` for ordinary external downloads. The remaining reliability gap is evidence that a failed process can leave resumable aria2 state and that a fresh yt-dlp invocation actually continues from prior bytes rather than silently restarting from zero.

Added `Aria2ResumeInstrumentedTest` on `agent-dev`. It uses a loopback HTTP media server, deliberately truncates the first aria2 transfer, starts a fresh yt-dlp process against the same output path, and requires the resumed transfer to send a non-zero HTTP `Range` request and reconstruct byte-for-byte identical media. The probe constrains aria2 to one connection and disables preallocation so the interruption/resume behavior is deterministic and observable.

The first draft was immediately self-reviewed before CI completed: an initial aria2 GET normally has no `Range` header, just like yt-dlp's extractor probe. Commit `d4b9c381` corrected the server to distinguish the first extractor probe from the first aria2 transfer by request order, then require a non-zero range during the fresh invocation.

Android CI `34098032782` is validating the corrected test. Generic CI for the corrected tip is also running/queued. No production download options were changed in this run.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- EJS diagnostic and runner-restoration work passed Android CI through `34088583867`.
- EJS hosted-runner gating passed Android CI `34093307496`.
- Corrected restart/resume instrumentation is currently under Android CI `34098032782`.
- No open PRs or issues were present at the start of this run.
- Recent PR #19 still has no submitted reviews; no actionable CodeRabbit or `Yashas's code review bot:` feedback was found.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Restart-based yt-dlp/aria2 byte-range continuation is not yet claimed as validated until the corrected instrumentation run passes and its request behavior is confirmed.

## Highest-value next step

Inspect Android CI `34098032782`. If the loopback test proves a non-zero `Range` resume with byte-identical output, close the restart/resume evidence gap and then test cancellation/service-restart preservation around HOLEN's own staging/job lifecycle. If it fails, fix the real aria2/yt-dlp resume behavior rather than weakening the assertion.
