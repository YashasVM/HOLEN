# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current baseline inspected before this update: `main` `4b46036d`; `agent-dev` was 90 commits ahead and 0 behind.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting so numbered yt-dlp `.part-FragN` artifacts are not counted as completed progress.
- Prevented numbered yt-dlp fragment temp files from being selected as completed media while preserving legitimate filenames containing `part-Frag` text.
- Direct HTTP downloads now honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Added a dedicated repeated yt-dlp process-launch timing probe; Android CI `34059630027` passed instrumentation, lint/unit/build, APK assembly, and 16 KB native-library verification.

## Current work: first-download / metadata latency

Recent successful Android CI artifacts were compared instead of tuning by guesswork. Across four validated emulator runs (`34037712634`, `34040813804`, `34047099663`, `34050305494`):

- yt-dlp process launch stayed roughly **1.85–2.35 s**.
- cold `YoutubeDL.init` varied roughly **0.90–3.24 s**.
- local extractor work beyond process startup was only **0–365 ms**.
- 64 MiB private-storage write was **22–40 ms**, with fsync **45–77 ms**.
- 64 MiB loopback fresh transfer was **255–330 ms**; resumed-half transfer was **121–135 ms**.
- FFmpeg cold extraction was **1.28–2.49 s**, but production already keeps it off metadata/app-startup critical paths and prewarms it after successful full analysis.

The evidence does **not** support transfer-buffer, fsync, SAF-copy, or fragment-concurrency tuning as the next latency optimization. Production already caches analysis results, performs local yt-dlp initialization through a guarded one-time path, and keeps FFmpeg/aria2 extraction off the metadata critical path. Metadata analysis and the eventual download remain separate yt-dlp executions because the latter must run the actual transfer/post-processing pipeline.

The repeated-launch probe now validates two back-to-back `yt-dlp --version` executions after initialization and preserves `process_launch_first_ms` / `process_launch_repeat_ms` in the Android instrumentation artifact. The CI run is green; no production behavior was changed. Exact timing values should be reviewed from the preserved artifact before using the probe to justify a warm-process or architecture change.

Current upstream evidence also supports treating process startup as an upstream/architecture-level cost rather than pretending a storage micro-optimization will solve it: yt-dlp has public reports of substantial per-invocation startup cost, and youtubedl-android itself uses a lazy-extractor build to reduce startup work.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed. The corresponding generic CI also passed.
- No open PRs or issues were present at the start of this run.
- Recent PR #19 was merged by the user previously; there is no current maintainer PR targeting `main` and no actionable CodeRabbit or `Yashas's code review bot:` feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministically end-to-end validated.
- Emulator timing is useful for bottleneck ranking, not a claim of phone-level absolute latency or a promised speedup.
- Eliminating a yt-dlp process invocation would require a larger architectural change or reuse of extraction state; neither is justified without measured benefit and correctness coverage for expiring media URLs, cookies/auth, formats, and extractor behavior.

## Highest-value next step

Inspect the analyze-to-download handoff and current yt-dlp/youtubedl-android capabilities for a safe way to reuse extraction state or reduce redundant network extraction without reusing stale media URLs. Only implement it if it removes material latency while preserving cookies/auth, format correctness, retries, and extractor compatibility; otherwise move to the next measured Android reliability/performance bottleneck.
