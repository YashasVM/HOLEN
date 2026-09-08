# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched.
- Latest inspected baseline: `main` `4b46036d`; `agent-dev` is ahead with `main` still the merge base and no drift from newer `main` work.

## Major completed work since the last weekly review

- Hardened Android staging/SAF publication, interrupted-download recovery, yt-dlp/aria2 retry/resume behavior, cookie/auth handling, and actionable failure classification.
- Added deterministic restart/resume instrumentation proving retained aria2 state, non-zero resumed ranges, and byte-identical final media after process/service interruption.
- Added the official yt-dlp `ejs:github` remote component policy for full YouTube operations with explicit Android cache handling, plus strict opt-in compatibility probes and EJS-specific failure guidance.
- Added focused TLS/certificate classification, including typed `SSLHandshakeException` handling, without recommending disabled certificate verification.
- Tightened Android 16 KB native verification so nested wrapper payloads and wrapper assets are recursively inspected rather than skipped.

## Performance evidence

Latest emulator baseline remains diagnostic rather than a claimed phone benchmark:

- App home: `3683 ms`.
- Cold yt-dlp runtime initialization: `1454 ms`; FFmpeg extraction/init: `1684 ms`; aria2c: `176 ms`.
- Re-entering FFmpeg + aria2c after prewarm: `0 ms`.
- yt-dlp process launch: `2614 ms`; back-to-back launch probe: `1955 ms` then `979 ms`.
- 64 MiB private-storage write: `24 ms` plus `54 ms` fsync; 64 MiB loopback transfer: `284 ms`; 32 MiB resumed remainder: `135 ms`.

These numbers rank emulator-side bottlenecks only; they do not claim production speedups.

## Current work: 16 KB native payload compatibility

The stricter verifier originally exposed a real arm64 defect in the official `youtubedl-android` 0.18.1 FFmpeg payload: several nested WebP libraries used `0x1000` `PT_LOAD` alignment. `agent-dev` therefore temporarily pins the wrapper modules to the exact upstream PR #350 head `83f41ae27710b4a1d47f4a0095209f4325e4564f`, with JitPack resolution scoped only to `com.github.Lizzergas.youtubedl-android`.

The pinned dependency builds and passes instrumentation. A retained ARM64 artifact was independently inspected: 273 actual runtime ELF files were checked and none had a `PT_LOAD` alignment below `0x4000`.

The latest retained universal run (`34265825909`) again passed lint/test/build and instrumentation and failed only strict 16 KB verification. That run successfully retained both ARM64 and universal APKs, so artifact retention is working as intended.

The verifier had one remaining semantic bug: after filtering static archives by file magic, it still treated every standalone ELF-magic file extracted from wrapper archives as a runtime-mapped binary. ELF relocatable build objects (`ET_REL`) legitimately have no `PT_LOAD` segments and must not be judged by runtime page alignment. Commit `ba9333a5` now applies recursive alignment checks only to loadable `ET_DYN`/`ET_EXEC` payloads, while direct APK native `.so` slots remain strict and reject non-runtime or corrupt ELF content.

Android CI `34272229914` is validating this runtime-ELF distinction against both ARM64 and universal release APKs. Do not treat 16 KB support as closed until that strict run is green.

## Live YouTube EJS validation

The deterministic EJS policy, build, instrumentation, cache handling, parser, and failure-classification paths are green. Live YouTube challenge solving remains deliberately unclaimed because hosted runner traffic has been rate-limited by YouTube. No extra fallback or dependency churn is justified until an opt-in probe succeeds on a non-rate-limited Android/network connection.

## Validation / reviewer state

- Android CI `34265825909`: lint/test/build and instrumentation passed; strict 16 KB verification failed; both ARM64 and universal test APKs were retained successfully.
- Independent retained ARM64 inspection remains clean: 273 runtime ELFs checked; zero had sub-`0x4000` `PT_LOAD` alignment.
- Android CI `34272229914` and generic CI `34272229940` are validating the runtime-ELF verifier correction.
- Upstream youtubedl-android PR #350 remains open and arm64-focused; its pinned head is unchanged.
- No open HOLEN PRs or issues and no actionable CodeRabbit or `Yashas's code review bot:` feedback were found in the latest live inspection.

## Known risks / review points

- The current 16 KB wrapper fix still depends on an unmerged upstream PR through a pinned JitPack commit. Replace it with an official Maven Central release once upstream publishes equivalent support.
- 16 KB compatibility is not closed until strict universal verification passes. Android's official guidance includes both ARM64 and x86-64 16 KB test environments, so universal/x86-64 validation should not simply be disabled to make CI green.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; live compatibility still needs one successful non-rate-limited Android run.
- Explicit user cancellation deliberately deletes staging and is not pause/resume; crash/service interruption recovery is separate and already proven to preserve resumable state.

## Highest-value next step

Inspect Android CI `34272229914`. If strict 16 KB verification is green, close this verifier task and return to the live YouTube EJS first-fetch/cache/reuse probe. If it remains red, use the retained universal APK to identify the exact remaining loadable ELF rather than weakening x86-64 coverage or changing dependencies speculatively.
