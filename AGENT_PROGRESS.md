# Agent progress

## Completed
- Created and maintained the long-running `agent-dev` branch while leaving `main` untouched.
- Ported the validated Android updater version-normalization fix from former PR #18.
- Added conservative direct-download resume fallback using strong Last-Modified/Date validation when no ETag exists; weak/malformed validators still disable resume.
- Added bounded direct-download retry for transient transport failures and HTTP 408/500/502/503/504 while preserving resumable staging.
- Added actionable yt-dlp HTTP failure classification for auth/access, unavailable media, rate limits, and temporary source failures.
- Removed FFmpeg extraction from metadata warm-up so first metadata analysis no longer waits on media tooling it does not need.
- Hardened same-process engine reset/update failure handling so destructive runtime resets fail with explicit restart guidance instead of reusing stale singleton initialization state.
- Kept aria2c as the ordinary-transfer downloader while forcing DASH/HLS through yt-dlp native downloading, matching the upstream mitigation for GHSA-vx4q-3cr2-7cg2 / CVE-2026-50574.
- Fixed restart-required engine failures being misclassified as generic network failures.
- Added a blocking Android instrumentation CI job on Ubuntu/KVM, retained failure reports, and aligned the onboarding test with the production creator-credit contract.
- Android CI run `33464291010` passed both the normal verification job and the Linux-KVM instrumentation job after the test alignment; the instrumentation gate is now working end to end.
- Verified Android lint, JVM tests, emulator/ARM64/ARMv7/universal APK builds, 16 KB native-library compatibility, and the connected instrumentation suite for the completed changes above.
- Reduced first-download tool initialization from the critical path by asynchronously prewarming FFmpeg then aria2c only after a successful FULL yt-dlp analysis. QUICK/shared-link analysis and ordinary app idle remain lean.
- Proved the prewarm at the initialization boundary: Android CI run `33510436391` measured `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, and `process_launch_ms=1978`. The same run passed normal verification and Linux-KVM instrumentation.
- Restored the live pre-existing Android CI safeguards after the local-extractor observability edit accidentally replaced unrelated workflow sections from stale content. Signing enforcement, Android 37.1 setup, pinned emulator-runner, 16 KB verifier behavior, branch/path filters, and artifact behavior are restored; only the intended local timing checks/metadata remain added.

## In progress
- Reduce the remaining yt-dlp-managed download startup latency without regressing metadata responsiveness or introducing speculative generic app-start work.
- Hosted-emulator measurements consistently place yt-dlp process launch near 1.95-1.98 s, making it the largest remaining measured startup component.
- Upstream youtubedl-android `executeImpl` constructs a fresh `ProcessBuilder` for every request and launches the packaged Python binary plus yt-dlp script, so the measured cost is structurally tied to a new subprocess rather than a HOLEN-side mutex or initialization call.
- Added an opt-in localhost extractor timing probe to separate the fixed subprocess baseline from extractor/HTTP/metadata work without depending on public-network conditions. CI now requires and exposes `local_extract_ms` and `local_extract_overhead_ms` alongside the existing startup measurements.
- The current youtubedl-android architecture executes yt-dlp through a separate Python process for each request. Replacing it with an in-process Chaquopy-based fork is not a small optimization: current alternatives trade away HOLEN's in-app yt-dlp update path and 32-bit ABI support, add a large embedded Python runtime, and materially change extractor/runtime behavior. Do not migrate merely to chase the process-launch number without representative-device evidence and a full compatibility plan.

## Validation
- Baseline Android CI run `33484712612` measured on the hosted API-35 x86_64 emulator: `youtube_dl_ms=984`, `ffmpeg_ms=1312`, `aria2c_ms=149`, `process_launch_ms=1944`, `total_ms=4389`.
- A second cold probe in Android CI run `33494800377` measured `youtube_dl_ms=1019`, `ffmpeg_ms=1283`, `aria2c_ms=134`, `process_launch_ms=1957`, `total_ms=4393`, showing the baseline is repeatable enough to guide this optimization.
- Android CI run `33510436391` passed both jobs and exposed API-readable timing metadata: `youtube_dl_ms=1002`, `ffmpeg_ms=1237`, `aria2c_ms=132`, `post_prewarm_tool_reentry_ms=0`, `process_launch_ms=1978`.
- The 0 ms post-prewarm re-entry measurement shows the one-time FFmpeg+aria2 initialization cost (1.369 s in that run; 1.417-1.461 s in the two prior runs) is fully removed at the wrapper initialization boundary once prewarm completes. This is evidence for the initialization-path improvement, not a claim that every user sees a fixed 1.4 s end-to-end speedup.
- Android CI run `33523235345` is not valid product-performance evidence: it failed before Android code/test execution because the workflow itself had been unintentionally rewritten. Commit `854f95d3` restores the prior live workflow while preserving only the intended local timing additions; fresh CI is required before using the new localhost measurements.
- The localhost extractor probe serves a tiny loopback `video/mp4` response and measures a real `YoutubeDL.execute` metadata path so public internet latency does not dominate the result.
- The measurement is diagnostic evidence from a hosted x86_64 emulator, not a user-facing ARM-device benchmark.
- No production Android behavior changed in the localhost-probe work; only instrumentation and CI observability changed.

## Known risks
- A user who performs a successful FULL analysis but never downloads now pays the one-time FFmpeg/aria2 extraction cost in the background. Scope is intentionally limited to FULL analysis because that is the strongest existing download-intent signal.
- If the user queues immediately after analysis, the download may still wait for some or all of initialization; the existing `initMutex` makes this a join rather than duplicate extraction.
- Hosted-emulator timings can guide optimization but are not representative ARM-device performance claims; confirm on representative ARM hardware before advertising a user-facing speedup.
- yt-dlp process launch remains the single largest measured startup component and may be mostly intrinsic to the current youtubedl-android subprocess architecture; do not add a dummy warm process or swap runtime libraries without evidence.
- The localhost extractor timing subtracts a separate `--version` execution from a later generic-extractor execution, so filesystem/page-cache effects can influence the derived overhead. Treat it as diagnostic decomposition, not an exact end-user latency claim.
- DASH/HLS intentionally use yt-dlp's native fragment downloader for safety, so those protocols do not receive aria2c transfer behavior; ordinary HTTP transfers still use aria2c.
- Network switching can expose device/carrier/DNS-specific failures that repository-only tests cannot reproduce; avoid adding another process-level retry loop without evidence.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code/CI commit, then merge only if satisfied.
