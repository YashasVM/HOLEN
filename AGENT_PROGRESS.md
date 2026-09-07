# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` was 129 commits ahead and 0 behind before the latest service-level recovery test commit.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting and completed-output selection so numbered yt-dlp `.part-FragN` artifacts are not mistaken for completed media.
- Direct HTTP downloads honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Measured repeated yt-dlp process startup; Android CI showed the process launch itself is a material part of first-analysis latency, while local extractor/storage/loopback timings were smaller. No unmeasured throughput claim was made.
- Rejected analyze-to-download extraction reuse because signed URLs, auth/cookie state, format selection, and extractor state can become stale between the two operations.
- Investigated current YouTube EJS support. youtubedl-android `0.18.1` supplies QuickJS but not matching EJS solver scripts, while naive `ejs:github` use would repeatedly fetch because the wrapper disables yt-dlp caching unless an explicit cache directory is supplied.
- Hosted GitHub runners cannot defensibly validate live YouTube EJS behavior because YouTube returns HTTP 429. The live EJS probe is opt-in behind `HOLEN_EJS_REMOTE_PROBE=true`; production does not enable `ejs:github`.
- Completed deterministic low-level yt-dlp/aria2 restart-resume validation. Android CI `34135963372` passed both verify and instrumentation after the loopback fixture was corrected to honor bounded byte ranges. The test retains partial media plus `.aria2` state, kills the first yt-dlp process, launches a fresh process, requires a non-zero completed-piece `Range`, and verifies byte-identical final output.
- Validated HOLEN's store/output recovery boundary in Android CI `34141010546`. A known interrupted media job keeps deliberately old partial media plus `.aria2` state through orphan cleanup and `requeueInterrupted()`, proving the underlying recovery primitives do not discard resume data.

## Current work: service-level restart recovery

The remaining lifecycle question is whether `DownloadService` itself performs the startup recovery/requeue sequence without discarding staging before resumed work is reclaimed.

Commit `63fc03d8` adds an Android instrumentation test that seeds a `RUNNING` media job with resumable staging, starts the real `DownloadService`, requires the job to be requeued and reclaimed with reset display progress, then holds the resumed extractor request on a loopback fixture while asserting both the partial media and `.aria2` state still exist. This is test coverage only; production behavior is unchanged.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- EJS diagnostic/runner work passed through Android CI `34093307496` with the live network probe disabled by default.
- Low-level restart/resume Android CI `34135963372` passed both verify and instrumentation, completing the fresh-process aria2 Range/integrity proof.
- HOLEN lifecycle staging-preservation Android CI `34141010546` passed both verify and instrumentation.
- Service-level recovery test `63fc03d8` is currently under fresh CI validation.
- No open PRs or issues were present in the latest inspection.
- PR #19 has no actionable review feedback; CodeRabbit only reports that automatic review is skipped for the repository's current star count. No `Yashas's code review bot:` feedback is pending.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- Service-level restart recovery is not considered fully validated until `63fc03d8` passes instrumentation; the lower-level staging and aria2 mechanisms are already proven.
- Explicit user cancellation deliberately deletes staging and therefore does not offer pause/resume semantics; interrupted service/process recovery is a separate path that preserves resumable state.
- Current yt-dlp's `Aria2cFD` deliberately appends some fixed aria2 flags after configured downloader arguments, including `--always-resume=false`; future production tuning must account for those enforced options rather than assuming earlier duplicate arguments win.

## Highest-value next step

Validate `63fc03d8` in Android CI. If green, close the restart/resume investigation as proven through the service boundary and move to the next material Android reliability/performance gap instead of adding more redundant resume tests. If it fails, fix the service-level lifecycle issue or the test fixture based on the observed failure without weakening staging-preservation assertions.
