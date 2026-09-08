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

The pinned dependency builds and passes instrumentation. A retained ARM64 artifact was downloaded and independently inspected: 273 actual runtime ELF files were checked and none had a `PT_LOAD` alignment below `0x4000`.

Commit `18b6b342` corrected one verifier false positive by identifying embedded runtime ELF files from their ELF magic bytes before applying `PT_LOAD` checks, so static archives such as `libOpenCL.a` and `libquickjs.a` are not treated as mmap-loaded binaries. Direct APK `lib/<abi>/*.so` validation remains strict, and the corrupt-native-library regression still rejects arbitrary invalid `.so` files.

Android CI `34260509618` still failed only at the strict 16 KB verification step even though lint/test/build and instrumentation passed. Because the retained ARM64 APK is clean, the remaining failure is now narrowed to either the universal APK contents or verifier behavior specific to that APK. Commit `dee7bcc2` retains the universal non-main test APK on verifier failure as well as the ARM64 artifact, without changing main/release artifact policy or weakening verification.

Do not treat 16 KB support as closed until the universal artifact is inspected and strict CI passes.

## Live YouTube EJS validation

The deterministic EJS policy, build, instrumentation, cache handling, parser, and failure-classification paths are green. Live YouTube challenge solving remains deliberately unclaimed because hosted runner traffic has been rate-limited by YouTube. No extra fallback or dependency churn is justified until an opt-in probe succeeds on a non-rate-limited Android/network connection.

## Validation / reviewer state

- Android CI `34260509618`: lint/test/build and instrumentation passed; strict 16 KB verification failed.
- Independent retained ARM64 APK inspection from that run: 273 runtime ELFs checked; zero had sub-`0x4000` `PT_LOAD` alignment.
- Generic CI `34260509629` and the following documentation CI completed successfully.
- A fresh Android CI run triggered by `dee7bcc2` will retain both ARM64 and universal test APKs if strict verification fails again, allowing exact universal-APK diagnosis.
- No open HOLEN PRs or issues and no actionable CodeRabbit or `Yashas's code review bot:` feedback were found in the latest live inspection.

## Known risks / review points

- The current 16 KB wrapper fix still depends on an unmerged upstream PR through a pinned JitPack commit. Replace it with an official Maven Central release once upstream publishes equivalent support.
- 16 KB compatibility is not closed: the retained ARM64 artifact is clean, but the universal APK path still needs exact inspection because strict CI remains red.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; live compatibility still needs one successful non-rate-limited Android run.
- Explicit user cancellation deliberately deletes staging and is not pause/resume; crash/service interruption recovery is separate and already proven to preserve resumable state.

## Highest-value next step

Inspect the fresh Android CI run for `dee7bcc2`. If verification still fails, download the retained universal APK and identify the exact failing ELF or verifier condition before changing any dependency. Do not weaken the verifier. Once strict 16 KB validation is green, return to the live YouTube EJS first-fetch/cache/reuse probe.
