# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` was 119 commits ahead and 0 behind before the process-cancel resume probe update.

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

The previous fixture attempted to force the first invocation to fail by truncating its HTTP response. Android CI `34114034226` showed that assumption was invalid: aria2 recovered the truncated response within the same invocation, so the first yt-dlp call completed successfully. The independent verify job still passed lint/tests/build and 16 KB native-library verification. This is useful evidence about aria2's internal recovery, but it is not evidence of process-restart resume.

Commit `c562fc65` changes the probe to the real boundary under test. The fixture now holds the first marked aria2 response open after writing multiple pieces; the test waits until partial media plus the `.aria2` control file are physically present, explicitly terminates that first yt-dlp process, then starts a fresh second process against the same output path. The second invocation must request a non-zero completed-piece HTTP `Range` and reconstruct byte-identical final media. Production download options remain unchanged.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- EJS diagnostic and runner-restoration work passed Android CI through `34088583867`; hosted-runner EJS gating passed `34093307496`.
- Restart/resume CI `34103056915`: verify passed; instrumentation exposed a test-fixture request-order assumption that was removed.
- Completed-piece restart/resume CI `34108775611`: verify passed; instrumentation was invalidated by an unhandled peer reset, fixed afterward.
- Android CI `34114034226`: verify passed; instrumentation showed the truncated first response was recovered inside the same aria2 process, invalidating the assumption that truncation would force a process-level failure.
- Fresh CI for the process-cancel probe is pending from `c562fc65`.
- No open PRs or issues were present in the latest inspection.
- Recent PR #19 has no submitted reviews; no actionable CodeRabbit or `Yashas's code review bot:` feedback was found.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Restart-based yt-dlp/aria2 byte-range continuation is not yet claimed as validated until the process-cancel probe passes with retained `.aria2` state, a non-zero completed-piece Range, and byte-identical output.

## Highest-value next step

Inspect the Android CI triggered by `c562fc65`. If the process-cancel probe passes, move one level higher and verify HOLEN's cancellation/service-restart/staging lifecycle preserves the same resumable state. If the first process termination removes or corrupts aria2 state, investigate youtubedl-android/libaria2 cancellation semantics instead of weakening the assertion.
