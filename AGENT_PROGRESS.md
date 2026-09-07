# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` was 121 commits ahead and 0 behind before the downloader-args probe fix in this run.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting and completed-output selection so numbered yt-dlp `.part-FragN` artifacts are not mistaken for completed media.
- Direct HTTP downloads honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Measured repeated yt-dlp process startup; Android CI showed the process launch itself is a material part of first-analysis latency, while local extractor/storage/loopback timings were smaller. No unmeasured throughput claim was made.
- Rejected analyze-to-download extraction reuse because signed URLs, auth/cookie state, format selection, and extractor state can become stale between the two operations.
- Investigated current YouTube EJS support. youtubedl-android `0.18.1` supplies QuickJS but not matching EJS solver scripts, while naive `ejs:github` use would repeatedly fetch because the wrapper disables yt-dlp caching unless an explicit cache directory is supplied.
- Hosted GitHub runners cannot defensibly validate live YouTube EJS behavior because YouTube returns HTTP 429. The live EJS probe is opt-in behind `HOLEN_EJS_REMOTE_PROBE=true`; production does not enable `ejs:github`. Android CI `34093307496` passed with the live probe gated off.

## Current work: deterministic yt-dlp / aria2 restart-resume validation

Production Android downloads already pass yt-dlp `--continue` and use `libaria2c.so` for ordinary external downloads. The remaining reliability gap is evidence that a terminated process can leave resumable aria2 state and that a fresh yt-dlp invocation actually continues from prior bytes rather than silently restarting from zero.

Android CI `34119364094` failed only in instrumentation; its independent verify job passed lint/tests/build and 16 KB native-library verification. The uploaded instrumentation artifact showed the exact failure: `The first aria2 request should reach the fixture`. The fixture never observed the aria2-only `X-Holen-Aria2-Attempt` marker, so the process-cancellation phase was never reached.

The marker was being supplied with `--downloader-args aria2c:...` while Android invokes the binary as `libaria2c.so`. Current yt-dlp supports a `default:` downloader-args bucket specifically as a downloader-independent fallback. Commit `ebb6ce47` changes only the probe to use `default:` so the test no longer depends on yt-dlp matching the Android binary name to the `aria2c` configuration key. The resume requirements are unchanged: retained partial media plus `.aria2` state before cancellation, fresh-process non-zero completed-piece `Range`, and byte-identical final output.

Production download options remain unchanged until the deterministic probe proves what arguments Android's libaria2 path actually receives.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- EJS diagnostic and runner-restoration work passed Android CI through `34088583867`; hosted-runner EJS gating passed `34093307496`.
- Restart/resume CI `34103056915`: verify passed; instrumentation exposed a test-fixture request-order assumption that was removed.
- Completed-piece restart/resume CI `34108775611`: verify passed; instrumentation was invalidated by an unhandled peer reset, fixed afterward.
- Android CI `34114034226`: verify passed; instrumentation showed the truncated first response was recovered inside the same aria2 process, invalidating the assumption that truncation would force a process-level failure.
- Android CI `34119364094`: verify passed; instrumentation artifact proved the aria2-only marker never reached the fixture, exposing downloader-args key matching as the next issue to isolate.
- Fresh Android CI `34125427424` and generic CI `34125427372` are running for `ebb6ce47`.
- No open PRs or issues were present in the latest inspection.
- Recent PR #19 has no submitted reviews; no actionable CodeRabbit or `Yashas's code review bot:` feedback was found.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Restart-based yt-dlp/aria2 byte-range continuation is not yet claimed as validated until the process-cancel probe passes with retained `.aria2` state, a non-zero completed-piece Range, and byte-identical output.
- If `default:` causes the marker and tuning arguments to reach libaria2 while `aria2c:` does not, production downloader tuning may also need the same keying correction. Do not change production until the probe demonstrates that difference.

## Highest-value next step

Inspect Android CI `34125427424`. If the marker now reaches the fixture, continue the same test through cancellation and fresh-process resume. If it still does not, capture the actual libaria2 invocation arguments from the wrapper rather than weakening the assertion. Only after the probe establishes downloader-args routing should production aria2 tuning be reconsidered.
