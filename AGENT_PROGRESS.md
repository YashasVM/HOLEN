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

The pinned dependency builds and passes instrumentation, but earlier strict CI still failed. The retained ARM64 artifact from Android CI `34254369173` was downloaded and independently inspected instead of guessing at another dependency change. Every actual runtime ELF found in the APK passed the 16 KB requirement: 273 loadable ELF files were inspected and none had a `PT_LOAD` alignment below `0x4000`.

The remaining CI failure was a verifier false positive. `readelf -h` accepts Unix static archives and reports their ELF object members; the wrapper contains `libOpenCL.a` and QuickJS `libquickjs.a`, both of which are static archives rather than mmap-loaded runtime ELF files. The old verifier then required `PT_LOAD` segments from those archives and failed even though the actual loadable binaries were aligned.

Commit `18b6b342` fixes this by identifying embedded runtime ELF files from the ELF magic bytes (`7f454c46`) before applying `PT_LOAD` checks. Direct APK `lib/<abi>/*.so` validation remains strict, and the existing corrupt-native-library regression still rejects arbitrary invalid `.so` files. Android CI `34260509618` and generic CI `34260509629` are validating this corrected verifier.

Do not treat 16 KB support as closed until the corrected strict Android CI run passes.

## Live YouTube EJS validation

The deterministic EJS policy, build, instrumentation, cache handling, parser, and failure-classification paths are green. Live YouTube challenge solving remains deliberately unclaimed because hosted runner traffic has been rate-limited by YouTube. No extra fallback or dependency churn is justified until an opt-in probe succeeds on a non-rate-limited Android/network connection.

## Validation / reviewer state

- Android CI `34254369173`: lint/test/build and instrumentation passed; strict 16 KB verification failed, and the retained ARM64 test APK was successfully uploaded for diagnosis.
- Independent retained-APK inspection: 273 actual runtime ELFs checked; zero had sub-`0x4000` `PT_LOAD` alignment. The only false-positive candidates were static archives `libOpenCL.a` and `libquickjs.a` that are not loadable ELF files.
- Android CI `34260509618`: in progress for the static-archive verifier correction.
- Generic CI `34260509629`: in progress for the same correction.
- No open HOLEN PRs or issues and no actionable CodeRabbit or `Yashas's code review bot:` feedback were found in the latest live inspection.

## Known risks / review points

- The current 16 KB wrapper fix still depends on an unmerged upstream PR through a pinned JitPack commit. Replace it with an official Maven Central release once upstream publishes equivalent support.
- 16 KB compatibility is strongly supported by direct retained-APK inspection but is not closed until corrected CI passes without weakening runtime ELF checks.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; live compatibility still needs one successful non-rate-limited Android run.
- Explicit user cancellation deliberately deletes staging and is not pause/resume; crash/service interruption recovery is separate and already proven to preserve resumable state.

## Highest-value next step

Inspect Android CI `34260509618`. If it passes, close 16 KB compatibility for this pinned wrapper state and return to the strict live YouTube EJS first-fetch/cache/reuse probe. If it still fails, inspect the newly retained APK/report before making any further dependency change; do not weaken the verifier.
