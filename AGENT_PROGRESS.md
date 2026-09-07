# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` was 125 commits ahead and 0 behind before the latest resume-fixture fix.

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

Android CI `34130755664` failed only in instrumentation; its verify job passed lint/tests/build, release APK assembly, and 16 KB native-library verification. The downloaded instrumentation artifact showed the isolated `--load-info-json` fixture did reach aria2, retained state, cancelled the first process, and then failed during the fresh invocation with aria2 exit code 8. Upstream aria2 defines code 8 as the remote server not supporting resume when resume is required.

The fixture itself was at fault: its HTTP 206 handler ignored a requested range end. A bounded request such as `Range: bytes=A-B` received `Content-Range: bytes A-EOF/...` and a body through EOF, which is not a valid response to the requested interval and can make aria2 reject the server as resumable. Commit `cdf9b802` now parses single byte ranges, returns exactly the requested inclusive interval, caps open-ended ranges at EOF, and rejects malformed/unsupported ranges instead of fabricating a mismatched 206 response. Production download options remain unchanged.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- EJS diagnostic and runner-restoration work passed Android CI through `34088583867`; hosted-runner EJS gating passed `34093307496`.
- Restart/resume CI `34103056915`: verify passed; instrumentation exposed a test-fixture request-order assumption that was removed.
- Completed-piece restart/resume CI `34108775611`: verify passed; instrumentation was invalidated by an unhandled peer reset, fixed afterward.
- Android CI `34114034226`: verify passed; instrumentation showed truncation was recovered inside the same aria2 process rather than forcing process-level restart.
- Android CI `34119364094`: verify passed; an aria2-only marker experiment did not reach the fixture.
- Android CI `34125427424`: verify passed; the alternative downloader-args marker experiment also failed, so that routing hypothesis was discarded rather than applied to production.
- Android CI `34130755664`: verify passed; instrumentation artifact identified the fresh-process failure as aria2 exit code 8 caused by an invalid bounded-range response in the loopback fixture. Fixed in `cdf9b802` without changing production behavior.
- No open PRs or issues were present in the latest inspection.
- PR #19 has no submitted reviews. Its only recent CodeRabbit comment says automatic review was skipped because the repository has fewer than 10 stars; there is no actionable review feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Restart-based yt-dlp/aria2 byte-range continuation is not yet claimed as validated until the corrected process-cancel probe passes with retained `.aria2` state, a non-zero completed-piece Range, and byte-identical output.
- Current yt-dlp's `Aria2cFD` deliberately appends some fixed aria2 flags after configured downloader arguments, including `--always-resume=false`; future production tuning must account for those enforced options rather than assuming earlier duplicate arguments win.

## Highest-value next step

Validate the corrected bounded-range fixture in Android CI. If it passes, the low-level fresh-process aria2 resume proof is complete and the next step is an end-to-end HOLEN cancellation/service-restart/staging test to verify the app lifecycle preserves the same resumable state. If it still fails, inspect the exact second-attempt Range exchange from the instrumentation artifact before changing any production code.
