# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` was 113 commits ahead and 0 behind before this run.

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

`Aria2ResumeInstrumentedTest` uses a loopback HTTP media server, deliberately truncates the first aria2 transfer, starts a fresh yt-dlp process against the same output path, and requires the resumed transfer to send a non-zero HTTP `Range` request and reconstruct byte-for-byte identical media. The probe constrains aria2 to one connection and disables preallocation so interruption/resume behavior is observable.

Android CI `34098032782` failed only in instrumentation; the independent verify job still passed lint/tests/build, release APK assembly, and 16 KB native-library verification. The failing test was still identifying yt-dlp extractor probes vs aria2 transfers by request ordinal, which is not a stable boundary across yt-dlp behavior.

Commit `f73f99e5` removes that request-order assumption. The test now injects an `X-Holen-Aria2-Attempt` header only through aria2 downloader arguments, so the loopback server can deterministically distinguish unmarked yt-dlp extractor probes from the first and second actual aria2 transfers. The first aria2 transfer is truncated regardless of whether it starts with a Range header; the fresh second transfer must still prove a non-zero Range and byte-identical completion. No production download options changed.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- EJS diagnostic and runner-restoration work passed Android CI through `34088583867`.
- EJS hosted-runner gating passed Android CI `34093307496`.
- Restart/resume CI `34098032782`: verify job passed; instrumentation failed, leading to the explicit aria2-attempt marker fix in `f73f99e5`.
- No open PRs or issues were present at the start of this run.
- Recent PR #19 still has no submitted reviews; no actionable CodeRabbit or `Yashas's code review bot:` feedback was found.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Restart-based yt-dlp/aria2 byte-range continuation is not yet claimed as validated until the explicit-marker instrumentation passes and confirms a non-zero Range plus byte-identical output.

## Highest-value next step

Validate `f73f99e5` in Android CI. If the marker-based probe passes, close the low-level yt-dlp/aria2 restart-resume evidence gap and move one level higher to HOLEN's own cancellation/service-restart/staging lifecycle. If it still fails, inspect whether aria2 is discarding its partial/control state rather than weakening the resume assertion.
