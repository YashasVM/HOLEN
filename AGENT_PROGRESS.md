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

## Performance / packaging evidence

Latest emulator timings remain diagnostic rather than claimed phone benchmarks:

- App home: `3683 ms`.
- Cold yt-dlp runtime initialization: `1454 ms`; FFmpeg extraction/init: `1684 ms`; aria2c: `176 ms`.
- Re-entering FFmpeg + aria2c after prewarm: `0 ms`.
- yt-dlp process launch: `2614 ms`; back-to-back launch probe: `1955 ms` then `979 ms`.
- 64 MiB private-storage write: `24 ms` plus `54 ms` fsync; 64 MiB loopback transfer: `284 ms`; 32 MiB resumed remainder: `135 ms`.
- ARM-only release packaging reduced the universal test artifact from `223,235,098` bytes to `110,918,585` bytes (about `50.3%` smaller) while x86/x86_64 support remains available in the emulator flavor.

## Completed: 16 KB native payload compatibility

The verifier exposed 4 KB-aligned WebP-family native payloads in youtubedl-android 0.18.1. `agent-dev` temporarily pins wrapper modules to upstream PR #350 head `83f41ae27710b4a1d47f4a0095209f4325e4564f`, with JitPack resolution scoped only to `com.github.Lizzergas.youtubedl-android`.

A retained ARM64 artifact was independently inspected: 273 runtime ELF files were checked and none had a `PT_LOAD` alignment below `0x4000`. Remaining pre-change failures were x86_64-only, so physical-device release APKs now target ARM while the emulator flavor retains x86/x86_64. Android CI `34278213373` passed instrumentation, lint/test/build, and strict recursive 16 KB verification.

## Current work: download retry correctness

Live EJS policy/build/parser validation is green, but live first-fetch/cache-reuse proof still requires a manually dispatched `ejs_remote_probe=true` run on a usable non-rate-limited Android/network environment. No speculative EJS implementation change is justified while that external validation is unavailable here.

The next concrete reliability defect was found in direct-download `503` handling. HOLEN accepted `Retry-After` only up to 30 seconds; a valid longer server instruction (for example `Retry-After: 60`) was then discarded and replaced with a 1-second exponential retry. That contradicts HTTP Retry-After semantics and can repeatedly hit an origin that explicitly reported overload/maintenance.

Commit `7ab232ab` changes the policy so a syntactically valid 503 Retry-After above HOLEN's 30-second foreground retry budget stops automatic retry rather than substituting a much shorter delay. Short valid values are still honored, malformed 503 headers still use the normal transient-error retry policy, and 429 remains retryable only with a valid bounded Retry-After.

Commit `2da7afe6` adds focused tests for short, long-seconds, long-HTTP-date, and malformed 503 Retry-After handling. Android CI `34423406403` and generic CI `34423406405` were running at the latest inspection; do not consider this task closed until they pass.

## Validation / reviewer state

- 16 KB release validation and the normal EJS policy/parser/instrumentation path are green in Android CI.
- Strict opt-in EJS validation now fails rather than skips when YouTube rate-limits the probe; a green live run must prove both official `yt-dlp/ejs` GitHub acquisition and explicit `source: cache` reuse.
- Latest official yt-dlp release inspected remains `2026.08.19`.
- Upstream youtubedl-android PR #350 remains the temporary arm64 16 KB source; replace it with an official release once equivalent support is published.
- No open HOLEN PRs/issues or actionable reviewer feedback were found in the latest live inspection.

## Known risks / review points

- The 16 KB arm64 wrapper fix still depends on an unmerged upstream PR through a pinned JitPack commit.
- The `universal` release APK intentionally targets ARM physical-device ABIs only; review this distribution policy if physical x86 Android support becomes a requirement.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; live compatibility still needs one successful non-rate-limited Android run.
- Explicit user cancellation deliberately deletes staging and is not pause/resume; crash/service interruption recovery is separate and already proven to preserve resumable state.
- The new long-503 Retry-After behavior intentionally returns the foreground download attempt to the user instead of silently waiting more than 30 seconds or violating the server-requested delay.

## Highest-value next step

Finish CI validation of the 503 Retry-After policy. If green, self-review its failure messaging so users receive an actionable service-unavailable/rate-limit result; keep the live EJS probe pending until a manual `ejs_remote_probe=true` run can actually be executed.
