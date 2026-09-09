# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched.
- Latest inspected baseline: `main` `4b46036d`; `agent-dev` is ahead with `main` still the merge base and no drift from newer `main` work.

## Major completed work since the last weekly review

- Hardened Android staging/SAF publication, interrupted-download recovery, yt-dlp/aria2 retry/resume behavior, cookie/auth handling, and actionable failure classification.
- Added deterministic restart/resume instrumentation proving retained aria2 state, non-zero resumed ranges, and byte-identical final media after process/service interruption.
- Added the official yt-dlp `ejs:github` remote component policy for full YouTube operations with explicit Android cache handling, plus strict opt-in compatibility probes and EJS-specific failure guidance.
- Added focused TLS/certificate classification, including typed `SSLHandshakeException` handling, without recommending disabled certificate verification.
- Closed Android 16 KB release-payload validation: nested wrapper payloads/assets are recursively checked, the affected arm64 wrapper payload is pinned to the exact upstream PR #350 head, and release APKs now exclude emulator-only x86/x86_64 ABIs while the dedicated emulator flavor retains them.

## Performance / packaging evidence

Latest emulator timings remain diagnostic rather than claimed phone benchmarks:

- App home: `3683 ms`.
- Cold yt-dlp runtime initialization: `1454 ms`; FFmpeg extraction/init: `1684 ms`; aria2c: `176 ms`.
- Re-entering FFmpeg + aria2c after prewarm: `0 ms`.
- yt-dlp process launch: `2614 ms`; back-to-back launch probe: `1955 ms` then `979 ms`.
- 64 MiB private-storage write: `24 ms` plus `54 ms` fsync; 64 MiB loopback transfer: `284 ms`; 32 MiB resumed remainder: `135 ms`.

The ARM-only release packaging materially reduced the universal test artifact from `223,235,098` bytes to `110,918,585` bytes (about `50.3%` smaller) while preserving x86/x86_64 support in the emulator flavor.

## Completed: 16 KB native payload compatibility

The stricter verifier exposed a real arm64 defect in the official `youtubedl-android` 0.18.1 FFmpeg payload: several nested WebP libraries used `0x1000` `PT_LOAD` alignment. `agent-dev` therefore temporarily pins the wrapper modules to the exact upstream PR #350 head `83f41ae27710b4a1d47f4a0095209f4325e4564f`, with JitPack resolution scoped only to `com.github.Lizzergas.youtubedl-android`.

A retained ARM64 artifact was independently inspected: 273 runtime ELF files were checked and none had a `PT_LOAD` alignment below `0x4000`. Remaining failures were isolated to five x86_64 WebP-family libraries in the pre-change universal artifact. Because x86/x86_64 are emulator-only for HOLEN's Android distribution, the release `universal` flavor now contains only `arm64-v8a` and `armeabi-v7a`; the emulator flavor continues to carry x86/x86_64.

Android CI `34278213373` passed instrumentation, lint/test/build, and strict recursive 16 KB verification for the resulting release APKs. This task is closed unless upstream packaging changes or x86 physical-device release support becomes a requirement.

## Current work: live YouTube EJS validation

Production full YouTube analysis/downloads use a persistent yt-dlp cache and explicitly allow only the official `ejs:github` remote component. Build, deterministic policy, parser, failure classification, and normal instrumentation paths are green.

The opt-in live probe was tightened in commit `c1eda1ba`: a non-rate-limited first run must prove yt-dlp actually downloaded the challenge solver from the official `yt-dlp/ejs` GitHub release, and the second run must explicitly report `source: cache`. Merely succeeding with some non-empty cache is no longer treated as proof of EJS acquisition/reuse.

Commit `6392798e` aligned the instrumentation shell parser with the strict test's `web_fetch_signal` / `cache_reuse_signal` output. Android CI `34288255803` then passed normal instrumentation, lint/test/build, and strict 16 KB verification, proving that parser repair does not regress the regular Android CI path.

Commit `5f336e3a` added the manual CI entry point for the live probe: Android CI exposes a `workflow_dispatch` boolean named `ejs_remote_probe`, and only a manual dispatch from `agent-dev` can set `HOLEN_EJS_REMOTE_PROBE=true`. Push/PR runs remain offline and deterministic. Android CI `34292730073` passed after this workflow change, so the manual-probe plumbing is validated without regressing normal Android CI.

Commit `221175b8` closed a remaining false-green path: a first-run YouTube HTTP 429 previously marked the probe as upstream-blocked and skipped all compatibility assertions, allowing an opt-in validation run to pass without proving anything. The probe still records rate-limit diagnostics, but an explicitly requested live validation now fails unless the first official GitHub EJS fetch and second cached reuse both complete successfully.

Live first-fetch/cache-reuse remains deliberately unclaimed because hosted runner traffic has previously been rate-limited by YouTube. No extra fallback, runtime replacement, or dependency churn is justified until the strict probe runs successfully on a usable Android/network connection.

## Validation / reviewer state

- Android CI `34278213373`: instrumentation, lint/test/build, and strict 16 KB verification passed.
- Android CI `34283398225`: normal instrumentation, lint/test/build, and strict 16 KB verification passed after the stricter EJS assertion change; the live EJS probe remained opt-in and was not itself proven by that green run.
- Android CI `34288255803`: normal instrumentation, lint/test/build, and strict 16 KB verification passed after the EJS report-parser repair.
- Android CI `34292730073`: normal instrumentation, lint/test/build, and strict 16 KB verification passed after exposing the manual EJS workflow input.
- Generic CI `34305207900`: passed for the latest pre-change `agent-dev` progress update.
- Universal test artifact: `223,235,098` bytes before ARM-only packaging; `110,918,585` bytes after it.
- Latest official yt-dlp release inspected: `2026.08.19`.
- Upstream youtubedl-android PR #350 remains open at the pinned head `83f41ae27710b4a1d47f4a0095209f4325e4564f`.
- No open HOLEN PRs or issues and no actionable `Yashas's code review bot:` feedback were found in the latest live inspection.

## Known risks / review points

- The current 16 KB arm64 wrapper fix still depends on an unmerged upstream PR through a pinned JitPack commit. Replace it with an official Maven Central release once upstream publishes equivalent support.
- The `universal` release APK intentionally targets ARM physical-device ABIs only. x86/x86_64 remain in the emulator flavor; review this distribution policy before any future main-branch release if physical x86 Android support becomes a requirement.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; live compatibility still needs one successful non-rate-limited Android run.
- Explicit user cancellation deliberately deletes staging and is not pause/resume; crash/service interruption recovery is separate and already proven to preserve resumable state.

## Highest-value next step

Validate commit `221175b8` in normal Android CI, then run Android CI manually on `agent-dev` with `ejs_remote_probe=true` and require both the official GitHub first-fetch signal and explicit cached-solver reuse before claiming live EJS compatibility. If the live probe fails, diagnose that concrete failure before making any further EJS implementation changes.
