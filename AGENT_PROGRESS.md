# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current baseline inspected before this update: `main` `4b46036d`, with `agent-dev` cleanly ahead and not behind.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting so numbered yt-dlp `.part-FragN` artifacts are not counted as completed progress.
- Prevented numbered yt-dlp fragment temp files from being selected as completed media while preserving legitimate filenames containing `part-Frag` text.
- Direct HTTP downloads now honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.

## Current work: first-download / metadata latency

Recent successful Android CI artifacts were compared instead of tuning by guesswork. Across four validated emulator runs (`34037712634`, `34040813804`, `34047099663`, `34050305494`):

- yt-dlp process launch stayed roughly **1.85–2.35 s**.
- cold `YoutubeDL.init` varied roughly **0.90–3.24 s**.
- local extractor work beyond process startup was only **0–365 ms**.
- 64 MiB private-storage write was **22–40 ms**, with fsync **45–77 ms**.
- 64 MiB loopback fresh transfer was **255–330 ms**; resumed-half transfer was **121–135 ms**.
- FFmpeg cold extraction was **1.28–2.49 s**, but production already keeps it off metadata/app-startup critical paths and prewarms it after successful full analysis.

The evidence does **not** support transfer-buffer, fsync, SAF-copy, or fragment-concurrency tuning as the next latency optimization. The dominant measured cost is process/runtime startup, and moving yt-dlp initialization into foreground app startup would only shift latency without proving a net win.

A dedicated opt-in instrumentation probe now measures two back-to-back `yt-dlp --version` executions after `YoutubeDL.init`, producing `process_launch_first_ms` and `process_launch_repeat_ms`. Android CI `34059630027` is validating the probe and will show whether ~2 s is persistent per-process overhead or mainly a first-launch effect. No production behavior was changed for this measurement.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement commits are under validation in Android CI `34059630027`; generic CI is also running for the current measurement tip.
- No open PRs or issues were present at the start of this run.
- Recent PR #19 has no submitted reviews or inline review comments; there is no actionable CodeRabbit or `Yashas's code review bot:` feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministically end-to-end validated.
- Emulator timing is useful for bottleneck ranking, not a claim of phone-level absolute latency or a promised speedup.
- The new repeat-launch probe is measurement-only and should not drive a production optimization until its CI result is stable and interpretable.

## Highest-value next step

Finish Android CI for the repeated yt-dlp launch probe. If repeat launch remains near the first launch, inspect the analyze→download path for avoidable second yt-dlp executions; if repeat launch is materially cheaper, evaluate idle-time warming only if it improves user-visible latency without hurting app startup, memory, or battery.
