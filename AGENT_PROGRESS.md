# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` was 127 commits ahead and 0 behind before the latest lifecycle test commit.

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

## Current work: HOLEN lifecycle preservation of resumable staging

The low-level aria2 mechanism is now proven. The remaining app-level question is whether HOLEN's own restart recovery preserves the same state. Production startup calls `OutputStore.cleanOrphanStaging()` and then requeues interrupted `RUNNING`/`FINALIZING` jobs; explicit user cancellation intentionally clears staging, while timeout/process interruption is expected to keep it.

Commit `1d6a74cc` adds an Android instrumentation test for that boundary. It creates a known `RUNNING` media job with deliberately old partial media and `.aria2` control files, runs startup orphan cleanup with a future clock, verifies both files survive because the job is still known, requeues interrupted work, and verifies the job becomes `QUEUED` without deleting either resumable file. This changes test coverage only; production behavior is unchanged.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- EJS diagnostic/runner work passed through Android CI `34093307496` with the live network probe disabled by default.
- Low-level restart/resume Android CI `34135963372` passed both verify and instrumentation, completing the fresh-process aria2 Range/integrity proof.
- The new HOLEN lifecycle staging-preservation instrumentation test is awaiting fresh CI on `1d6a74cc`.
- No open PRs or issues were present in the latest inspection.
- PR #19 has no submitted reviews; there is no actionable CodeRabbit or `Yashas's code review bot:` feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Emulator timing ranks bottlenecks but is not a claim of phone-level absolute latency or promised speedup.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. Keep production remote EJS disabled until acquisition/cache reuse can be validated on a suitable network.
- HOLEN's app-level restart path is not considered fully validated until the new lifecycle staging-preservation test passes in Android CI.
- Explicit user cancellation deliberately deletes staging and therefore does not offer pause/resume semantics; interrupted service/process recovery is a separate path that preserves resumable state.
- Current yt-dlp's `Aria2cFD` deliberately appends some fixed aria2 flags after configured downloader arguments, including `--always-resume=false`; future production tuning must account for those enforced options rather than assuming earlier duplicate arguments win.

## Highest-value next step

Validate `1d6a74cc` in Android CI. If green, add one service-level recovery test that exercises `DownloadService` startup/requeue behavior rather than only the underlying store/output primitives; only change production code if that higher-level test exposes a real lifecycle bug.
