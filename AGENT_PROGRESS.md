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

The stricter verifier exposed a real arm64 defect in the official `youtubedl-android` 0.18.1 FFmpeg payload: several nested WebP libraries used `0x1000` `PT_LOAD` alignment. `agent-dev` therefore temporarily pins the wrapper modules to the exact upstream PR #350 head `83f41ae27710b4a1d47f4a0095209f4325e4564f`, with JitPack resolution scoped only to `com.github.Lizzergas.youtubedl-android`.

The pinned arm64 dependency is clean. A retained ARM64 artifact was independently inspected: 273 runtime ELF files were checked and none had a `PT_LOAD` alignment below `0x4000`.

Android CI `34272229914` passed lint/test/build and instrumentation but still failed strict universal 16 KB verification. Its retained universal APK was inspected directly. The remaining failures are five x86_64 FFmpeg WebP-family libraries (`libwebp.so`, `libwebpdecoder.so`, `libwebpdemux.so`, `libwebpmux.so`, and `libsharpyuv.so`) with `0x1000` `PT_LOAD` alignment. The same artifact's arm64 payload is clean. Upstream PR #350 is explicitly arm64-focused and does not replace the x86_64 WebP payloads.

The release configuration was therefore simplified rather than weakening the verifier: the `universal` release flavor now contains the two physical-device ARM ABIs (`arm64-v8a` and `armeabi-v7a`), while x86/x86_64 remain available through the dedicated emulator flavor. In the retained pre-change universal APK, x86 plus x86_64 payloads accounted for about 112.3 MB of the 225.2 MB APK, so this also removes substantial emulator-only release weight. CI must validate the resulting release APK before the task is closed.

## Live YouTube EJS validation

The deterministic EJS policy, build, instrumentation, cache handling, parser, and failure-classification paths are green. Live YouTube challenge solving remains deliberately unclaimed because hosted runner traffic has been rate-limited by YouTube. No extra fallback or dependency churn is justified until an opt-in probe succeeds on a non-rate-limited Android/network connection.

## Validation / reviewer state

- Android CI `34272229914`: lint/test/build and instrumentation passed; strict 16 KB verification failed only on the retained universal APK's x86_64 WebP-family payloads.
- Retained ARM64 inspection remains clean: 273 runtime ELFs checked; zero had sub-`0x4000` `PT_LOAD` alignment.
- Generic CI `34272295003` for the preceding verifier/progress state passed.
- Upstream youtubedl-android PR #350 remains open, arm64-focused, and pinned to unchanged head `83f41ae27710b4a1d47f4a0095209f4325e4564f`.
- No open HOLEN PRs or issues and no actionable CodeRabbit or `Yashas's code review bot:` feedback were found in the latest live inspection.

## Known risks / review points

- The current 16 KB arm64 wrapper fix still depends on an unmerged upstream PR through a pinned JitPack commit. Replace it with an official Maven Central release once upstream publishes equivalent support.
- The `universal` release APK now intentionally targets ARM physical-device ABIs only. x86/x86_64 remain in the emulator flavor; review this distribution policy before any future main-branch release if desktop Android/x86 hardware support becomes a requirement.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; live compatibility still needs one successful non-rate-limited Android run.
- Explicit user cancellation deliberately deletes staging and is not pause/resume; crash/service interruption recovery is separate and already proven to preserve resumable state.

## Highest-value next step

Validate the ARM-only universal release in Android CI. If strict 16 KB verification is green and the APK size reduction matches the retained-artifact estimate, close the 16 KB task and return to the live YouTube EJS first-fetch/cache/reuse probe.
