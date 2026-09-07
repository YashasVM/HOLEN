# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` is 124 commits ahead and 0 behind after the latest resume-probe change.

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

Android CI `34125427424` again failed only in instrumentation; its verify job passed lint/tests/build and 16 KB native-library verification. Switching the probe from the `aria2c:` to `default:` downloader-args bucket did not make the aria2-only marker reach the fixture, so the earlier suspicion that production tuning might be ignored because Android names the executable `libaria2c.so` is not supported by this experiment.

Upstream yt-dlp's current `ExternalFD` selects downloader arguments by downloader class basename (`aria2c`) and then executable name; `Aria2cFD` appends those configured arguments to the aria2 command. The test therefore no longer tries to infer downloader identity through injected arguments. Commit `1c2bf360` instead feeds yt-dlp a local `--load-info-json` record containing the loopback media URL. This bypasses extractor HTTP traffic completely, so every request the fixture sees is genuine external-downloader traffic. The test still requires retained partial media plus `.aria2` state before cancellation, a fresh-process non-zero completed-piece `Range`, and byte-identical final output.

Production download options remain unchanged.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed.
- EJS diagnostic and runner-restoration work passed Android CI through `34088583867`; hosted-runner EJS gating passed `34093307496`.
- Restart/resume CI `34103056915`: verify passed; instrumentation exposed a test-fixture request-order assumption that was removed.
- Completed-piece restart/resume CI `34108775611`: verify passed; instrumentation was invalidated by an unhandled peer reset, fixed afterward.
- Android CI `34114034226`: verify passed; instrumentation showed the truncated first response was recovered inside the same aria2 process, invalidating the assumption that truncation would force a process-level failure.
- Android CI `34119364094`: verify passed; instrumentation proved the aria2-only marker never reached the fixture.
- Android CI `34125427424`: verify passed; the `default:` downloader-args marker experiment also failed to identify aria2 traffic, so that routing hypothesis was discarded rather than applied to production.
- No open PRs or issues were present in the latest inspection.
- No actionable CodeRabbit or `Yashas's code review bot:` feedback was found.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Restart-based yt-dlp/aria2 byte-range continuation is not yet claimed as validated until the process-cancel probe passes with retained `.aria2` state, a non-zero completed-piece Range, and byte-identical output.
- Current yt-dlp's `Aria2cFD` deliberately appends some fixed aria2 flags after configured downloader arguments, including `--always-resume=false`; future production tuning must account for those enforced options rather than assuming earlier duplicate arguments win.

## Highest-value next step

Validate the `--load-info-json` resume probe. If it reaches the fixture and survives cancellation with retained `.aria2` state, finish the fresh-process Range/integrity proof. If it still cannot establish resumable state, inspect youtubedl-android/libaria2 process cleanup rather than adding more request-identification heuristics.
