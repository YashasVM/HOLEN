# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched.
- Latest inspected baseline: `main` `4b46036d`; `agent-dev` remains based on that commit with no newer `main` work to sync.
- Latest inspection found no open HOLEN PRs/issues or actionable CodeRabbit / `Yashas's code review bot:` feedback.

## Major completed work since the last weekly review

- Hardened Android staging/SAF publication, interrupted-download recovery, yt-dlp/aria2 retry/resume behavior, cookie/auth handling, and actionable failure classification.
- Added deterministic restart/resume instrumentation proving retained aria2 state, non-zero resumed ranges, and byte-identical final media after process/service interruption.
- Added the official yt-dlp `ejs:github` remote component policy for full YouTube operations with persistent Android cache handling, strict opt-in compatibility probes, and EJS-specific failure guidance.
- Added focused TLS/certificate classification, including typed `SSLHandshakeException` handling, without recommending disabled certificate verification.
- Closed Android 16 KB release-payload validation: nested wrapper payloads/assets are recursively checked, the affected arm64 wrapper payload is pinned to the exact upstream PR #350 head, and release APKs exclude emulator-only x86/x86_64 ABIs while the emulator flavor retains them.
- Corrected direct-download server retry handling: valid long `503 Retry-After` instructions are no longer discarded and replaced with an aggressive short retry. Values beyond HOLEN's 30-second foreground retry budget now return control to the user with the existing actionable service-unavailable message.

## Performance / packaging evidence

Latest emulator timings remain diagnostic rather than claimed phone benchmarks:

- App home: recent CI measured `2357 ms`.
- Recent cold runtime probe: yt-dlp `999 ms`; FFmpeg `1278 ms`; aria2c `137 ms`; yt-dlp process launch `1947 ms`; local extraction `2278 ms`.
- Back-to-back yt-dlp launch probe: `1934 ms` then `974 ms`.
- 64 MiB private-storage write: `26 ms` plus `48 ms` fsync; 64 MiB loopback transfer: `298 ms`; 32 MiB resumed remainder: `117 ms`.
- ARM-only release packaging reduced the universal test artifact from `223,235,098` bytes to `110,918,585` bytes (about `50.3%` smaller) while x86/x86_64 support remains available in the emulator flavor.

## Completed: 16 KB native payload compatibility

The verifier exposed 4 KB-aligned WebP-family native payloads in youtubedl-android 0.18.1. `agent-dev` temporarily pins wrapper modules to upstream PR #350 head `83f41ae27710b4a1d47f4a0095209f4325e4564f`, with JitPack resolution scoped only to `com.github.Lizzergas.youtubedl-android`.

A retained ARM64 artifact was independently inspected: 273 runtime ELF files were checked and none had a `PT_LOAD` alignment below `0x4000`. Remaining pre-change failures were x86_64-only, so physical-device release APKs now target ARM while the emulator flavor retains x86/x86_64. Android CI `34278213373` passed instrumentation, lint/test/build, and strict recursive 16 KB verification.

## Current work

Live EJS policy/build/parser validation is green, but live first-fetch/cache-reuse proof still requires a manually dispatched `ejs_remote_probe=true` run on a usable non-rate-limited Android/network environment. No speculative EJS implementation change is justified while that external validation is unavailable here.

The direct-download `503 Retry-After` task is closed. Commit `7ab232ab` stops automatic retry when a syntactically valid server-requested delay exceeds HOLEN's 30-second foreground budget instead of substituting a much shorter delay. Commit `2da7afe6` covers short, long-seconds, long-HTTP-date, malformed 503 values, and retained 429 behavior. Android CI `34423406403` passed the full Android pipeline, and generic CI for the change also passed.

Service teardown recovery remains under validation. Commit `0d277881` prevents `onDestroy()` cancellation from being persisted as `FAILED`: explicit user cancellation still wins and clears staging, while timeout/service shutdown transitions run in `NonCancellable` context and requeue the active job so resumable staging survives. Commit `a83ee5b4` added lifecycle instrumentation. Earlier retries exposed fixture problems rather than teardown failures: the direct loopback fixture violated production URL policy, and Android CI `34443274975` showed the media-backed probe still failed before teardown at queue claim. `claimNextQueued()` is FIFO by `created_at`; commit `42375e64` now makes the teardown probe deterministically oldest so unrelated queued rows cannot occupy both service workers, and reports the final status/error if claim still fails.

## Validation / reviewer state

- Android CI `34423406403` passed the Retry-After policy tests along with the full Android workflow.
- Android CI `34443274975`: build/lint/unit/16 KB verification passed; instrumentation had exactly one failure, `DownloadService did not claim the teardown probe`, before teardown was exercised.
- The same run produced healthy diagnostic timings for app startup, runtime initialization, local extraction, transfer, and resume, so the failure was isolated to the teardown test path rather than a broad Android build/runtime regression.
- Commit `42375e64` is under Android CI validation with deterministic queue priority and better failure diagnostics.
- 16 KB release validation and the normal EJS policy/parser/instrumentation path remain green.
- Strict opt-in EJS validation now fails rather than skips when YouTube rate-limits the probe; a green live run must prove both official `yt-dlp/ejs` GitHub acquisition and explicit `source: cache` reuse.
- Latest official yt-dlp release inspected remains `2026.08.19`.
- Upstream youtubedl-android PR #350 remains the temporary arm64 16 KB source; replace it with an official release once equivalent support is published.
- No open HOLEN PRs/issues or actionable reviewer feedback were found in the latest live inspection.

## Known risks / review points

- The 16 KB arm64 wrapper fix still depends on an unmerged upstream PR through a pinned JitPack commit.
- The `universal` release APK intentionally targets ARM physical-device ABIs only; review this distribution policy if physical x86 Android support becomes a requirement.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; live compatibility still needs one successful non-rate-limited Android run.
- Explicit user cancellation deliberately deletes staging and is not pause/resume; service teardown prioritizes that explicit cancellation over automatic requeue.
- Service-teardown recovery is not considered closed until the corrected active-transfer instrumentation proves `QUEUED` persistence with staging intact.
- Long server-directed retry delays deliberately return the foreground download attempt to the user instead of silently waiting beyond the 30-second retry budget or violating the server-requested delay.

## Highest-value next step

Inspect Android CI for `42375e64`. If the deterministically prioritized probe reaches the held media connection, validate teardown persistence; if it still fails before connection, use the new status/error diagnostics to fix the concrete test or queue-path cause without changing production lifecycle behavior speculatively.