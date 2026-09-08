# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current inspected baseline: `main` `4b46036d`; `agent-dev` remains ahead with no drift from `main` detected in this run.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting and completed-output selection so numbered yt-dlp `.part-FragN` artifacts are not mistaken for completed media.
- Direct HTTP downloads honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Rejected analyze-to-download extraction reuse because signed URLs, auth/cookie state, format selection, and extractor state can become stale between operations.
- Completed deterministic low-level yt-dlp/aria2 restart-resume validation. Android CI `34135963372` passed both verify and instrumentation: retained partial media plus `.aria2` state, killed first process, fresh process issued a non-zero completed-piece `Range`, and final media was byte-identical.
- Validated HOLEN's store/output recovery boundary in Android CI `34141010546`. Interrupted media jobs retain partial media plus `.aria2` state through orphan cleanup and `requeueInterrupted()`.
- Closed service-level restart recovery in Android CI `34153823593`: both instrumentation and verify passed, including lint/tests/build, release APK assembly, 16 KB native-library verification, and the real `DownloadService` reclaim/staging-preservation probe.
- Enabled the official yt-dlp `ejs:github` remote component for full YouTube analysis and YouTube downloads, with an explicit reusable Android cache. Quick shared-link analysis and non-YouTube extractors remain unchanged. Android CI `34158081428` and `34158104192`, plus generic CI `34158104213`, passed for the production policy and host-coverage tests.
- Strengthened the opt-in Android EJS compatibility probe so a usable-network run must prove successful first extraction, non-empty cache creation, successful second extraction, and retained cache state. Android CI `34161519848` passed verify/instrumentation for this test change; the live EJS probe itself remained intentionally skipped in normal hosted CI.
- Fixed the strict EJS probe report parser so it consumes the new cache file/byte counters. Android CI `34164996314` and generic CI `34164996308` passed for the parser change.
- Corrected direct-file HTTP 402 failure classification: arbitrary HTTPS file servers now report payment/additional-access requirements instead of a rate limit, while yt-dlp extractor HTTP 402 remains treated as an overuse block. Generic CI `34171668938` and Android CI `34171668951` both passed the focused regression coverage.
- Corrected a missing-output failure path where `IOException("The media engine completed without an output file.")` was incorrectly falling through to the generic network-error message. The Android UI now tells the user the engine produced no usable output and suggests re-analysis plus engine update/reset if it repeats. Generic CI `34181614787` and Android CI `34181614839` both passed the regression coverage.
- Added dedicated YouTube EJS failure guidance for signature-solving, n-challenge, and solver-distribution failures so they no longer degrade into a generic error. Generic CI `34188759330` and Android CI `34188759351` both passed the focused regression coverage.
- Classified TLS/certificate verification failures separately from transient network failures. Android now points users to device clock and VPN/private-DNS/captive-portal interception checks and explicitly avoids recommending disabled certificate verification; regression coverage includes both Android `SSLHandshakeException`-style text and yt-dlp `CERTIFICATE_VERIFY_FAILED` output. Generic CI `34196693426` and Android CI `34196693401` both passed.
- Hardened the same TLS path to classify a real `SSLHandshakeException` by exception type even when its message is generic, avoiding an incorrect partial-transfer retry recommendation. Generic CI `34223327131` and Android CI `34223327149` both passed.

## Performance evidence

Android CI `34161519848` provides an emulator baseline rather than a claimed phone benchmark:

- App home: `3683 ms`.
- Cold yt-dlp runtime initialization: `1454 ms`; FFmpeg extraction/init: `1684 ms`; aria2c: `176 ms`.
- Re-entering FFmpeg + aria2c after prewarm: `0 ms`, supporting the existing post-analysis tool-prewarm strategy.
- yt-dlp process launch: `2614 ms`; back-to-back launch probe: `1955 ms` then `979 ms`.
- Local extractor total: `2253 ms`; measured local extractor overhead was `0 ms` relative to the process-launch baseline used by the probe.
- 64 MiB private-storage write: `24 ms` plus `54 ms` fsync; 64 MiB loopback transfer: `284 ms`; 32 MiB resumed remainder: `135 ms`.

These numbers rank emulator-side bottlenecks only. They do not claim absolute phone latency or a measured production speedup.

## Current work

### 16 KB native payload compatibility

The stricter recursive verifier exposed a real arm64 compatibility defect in HOLEN's packaged FFmpeg payload. Android CI `34234889210` failed at `Verify 16 KB native library compatibility` while lint/tests/build and instrumentation passed. Independent inspection of the prior release APK reproduced the failure: WebP libraries inside `arm64-v8a/libffmpeg.zip.so` use `0x1000` `PT_LOAD` alignment, including `libwebp.so`, `libwebpdemux.so`, `libsharpyuv.so`, `libwebpmux.so`, and `libwebpdecoder.so`.

This matches upstream `youtubedl-android` PR #350. That PR moves arm64 packaged archives to assets and replaces the FFmpeg WebP payload libraries with 16 KB-aligned builds. Rather than weakening the verifier or claiming compatibility, `agent-dev` pins the wrapper modules to the exact reviewed PR head commit `83f41ae27710b4a1d47f4a0095209f4325e4564f` and scopes JitPack resolution exclusively to `com.github.Lizzergas.youtubedl-android`. No floating branch or unrelated wrapper upgrade is used.

Android CI `34241886269` resolved and built that exact pin: instrumentation and lint/tests/build passed, but the recursive 16 KB verification still failed. Generic CI `34241886447` passed. The upstream patch also moves arm64 wrapper archives from `jniLibs` into `assets/youtubedl-android/<abi>/`, which exposed a second validation gap in HOLEN's verifier. Commit `64fa0757` closed that blind spot by recursively checking both native-library wrapper archives and the new wrapper assets. Android CI `34248227705` still failed specifically at the strict 16 KB verification while instrumentation and the full lint/test/build stage passed, confirming that at least one direct or asset-embedded ELF remains unacceptable under the current pin.

Commit `f783555a` changes only `agent-dev`/non-main CI diagnostics so the built ARM64 test APK is uploaded even when strict verification fails. This does not publish or sign a broken main artifact; it preserves the failed-build APK so the exact offending ELF can be inspected instead of guessing at another dependency change. Android CI `34254369173` is validating that diagnostic path.

Do not treat 16 KB support as closed until the exact remaining offender is identified and a strict run passes without weakening the verifier.

### Live YouTube EJS validation

Upstream yt-dlp's current EJS guidance requires a supported JavaScript runtime plus solver scripts for YouTube challenge solving. HOLEN already exposes bundled QuickJS, and production now enables the official `ejs:github` distribution with an explicit cache for full YouTube operations.

The deterministic policy, build, instrumentation, strict-probe parsing, and EJS-specific failure-classification paths are green. Live EJS extraction remains deliberately unclaimed because hosted GitHub runner traffic has been rate-limited by YouTube. No further production fallback is justified without a non-rate-limited Android/network run that can distinguish solver compatibility from network blocking.

## Validation / reviewer state

- Android CI `34234889210`: failed specifically at recursive 16 KB native-library verification; instrumentation and lint/tests/build passed.
- Independent artifact inspection reproduced `0x1000` alignment in the nested arm64 FFmpeg WebP libraries, confirming this is a real packaged-payload defect rather than a verifier false positive.
- Android CI `34241886269`: exact PR #350 pin resolved successfully; instrumentation and lint/tests/build passed, but 16 KB verification still failed.
- Generic CI `34241886447`: passed for the same dependency pin.
- Android CI `34248227705`: asset-aware recursive verification still failed specifically at 16 KB validation; instrumentation and lint/tests/build passed.
- Generic CI `34248378870`: passed for the asset-aware verifier state.
- Commit `f783555a` retains the non-main ARM64 test APK after verifier failure so the remaining ELF can be inspected; Android CI `34254369173` and generic CI `34254369065` are pending.
- Normal hosted instrumentation intentionally leaves the live EJS probe disabled unless `HOLEN_EJS_REMOTE_PROBE` is explicitly enabled.
- No open HOLEN PRs or issues and no actionable CodeRabbit or `Yashas's code review bot:` feedback were found in the latest live inspection.

## Known risks / review points

- The temporary 16 KB fix depends on the exact unmerged upstream PR #350 commit through JitPack. It is pinned to an immutable commit and repository resolution is scoped, but the user should replace it with the official Maven Central release once upstream merges/publishes equivalent support.
- 16 KB compatibility remains unproven. The PR #350 pin builds and runs instrumentation, but strict asset-aware verification still fails; the failed-build APK must be inspected before any further payload/dependency change.
- First-time YouTube EJS solver acquisition requires access to yt-dlp's official GitHub-hosted component; later runs should reuse yt-dlp's explicit cache unless Android evicts it.
- Live YouTube EJS behavior still needs one successful opt-in run on a network not blocked/rate-limited by YouTube before treating challenge compatibility as fully proven.
- Retaining cache artifacts across the second probe proves persistent cache state survives reuse; it does not by itself prove yt-dlp made zero network requests on the second extraction.
- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Explicit user cancellation deliberately deletes staging and therefore is not pause/resume; crash/service interruption recovery is separate and proven to preserve resumable state.
- Current yt-dlp's `Aria2cFD` already uses 16 connections/splits and appends fixed flags including `--always-resume=false`; do not add duplicate connection tuning or assume earlier duplicate flags win.

## Highest-value next step

Wait for Android CI `34254369173` to finish, download its retained ARM64 test APK even if strict verification fails, identify the exact direct or asset-embedded ELF with sub-`0x4000` `PT_LOAD` alignment, and remediate that concrete payload/dependency without weakening the verifier. Only after a strict green run should work return to live YouTube EJS validation.
