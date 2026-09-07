# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` is 133 commits ahead and 0 behind after the latest service-recovery test fixes.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting and completed-output selection so numbered yt-dlp `.part-FragN` artifacts are not mistaken for completed media.
- Direct HTTP downloads honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Measured repeated yt-dlp process startup; Android CI showed process launch is a material part of first-analysis latency, while local extractor/storage/loopback timings were smaller. No unmeasured throughput claim was made.
- Rejected analyze-to-download extraction reuse because signed URLs, auth/cookie state, format selection, and extractor state can become stale between operations.
- Investigated current YouTube EJS support. youtubedl-android `0.18.1` supplies QuickJS but not matching EJS solver scripts, while naive `ejs:github` use would repeatedly fetch because the wrapper disables yt-dlp caching unless an explicit cache directory is supplied.
- Hosted GitHub runners cannot defensibly validate live YouTube EJS behavior because YouTube returns HTTP 429. The live EJS probe is opt-in behind `HOLEN_EJS_REMOTE_PROBE=true`; production does not enable `ejs:github`.
- Completed deterministic low-level yt-dlp/aria2 restart-resume validation. Android CI `34135963372` passed both verify and instrumentation: retained partial media plus `.aria2` state, killed first process, fresh process issued a non-zero completed-piece `Range`, and final media was byte-identical.
- Validated HOLEN's store/output recovery boundary in Android CI `34141010546`. Interrupted media jobs retain partial media plus `.aria2` state through orphan cleanup and `requeueInterrupted()`.

## Current work: service-level restart recovery

Android CI `34145326121` showed the new service-level test failing only in instrumentation while verify, lint/tests/build, release APK assembly, and 16 KB native-library verification remained green. The failure exposed a test race: the probe required polling the brief `progress == 0` state after `DownloadService` requeues and reclaims an interrupted job, but `StagingProgressSampler` can immediately replace that display value from retained staging before the test observes it.

Commits `fc780db4` and `bb47db9f` remove that incidental timing dependency while keeping the meaningful contract: the service must reclaim the interrupted job, its state must no longer retain the stale pre-restart progress, partial media and `.aria2` state must survive, and resumed work must enter the downloader. Polling now stops immediately once recovery is observed instead of spinning for the entire timeout. Production Android behavior is unchanged.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- EJS diagnostic/runner work passed through Android CI `34093307496` with the live network probe disabled by default.
- Low-level restart/resume Android CI `34135963372` passed both verify and instrumentation.
- HOLEN lifecycle staging-preservation Android CI `34141010546` passed both verify and instrumentation.
- Service-level test run `34145326121`: Android verify passed; instrumentation failed on the transient zero-progress observation described above.
- Generic CI for `bb47db9f` passed; fresh Android CI `34149610106` is in progress.
- No open PRs or issues are present. PR #19 is closed/merged by the user and has no actionable reviewer feedback. No `Yashas's code review bot:` feedback is pending.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Service-level restart recovery is not considered fully validated until fresh Android instrumentation passes; lower-level staging and aria2 mechanisms are already proven.
- Explicit user cancellation deliberately deletes staging and therefore does not offer pause/resume semantics; interrupted service/process recovery is a separate path that preserves resumable state.
- Current yt-dlp's `Aria2cFD` deliberately appends some fixed aria2 flags after configured downloader arguments, including `--always-resume=false`; future production tuning must account for those enforced options rather than assuming earlier duplicate arguments win.

## Highest-value next step

Inspect Android CI `34149610106`. If green, close the restart/resume investigation as proven through the service boundary and move to the next material Android reliability/performance gap. If it fails, use the observed instrumentation failure to fix the fixture or service orchestration without weakening staging-preservation assertions.
