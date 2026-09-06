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

The evidence therefore does **not** support spending effort on transfer buffers, fsync, SAF copies, or fragment concurrency as the next latency optimization. The dominant measured cost is process/runtime startup. No production behavior was changed merely to move that work earlier: warming yt-dlp at app launch would shift latency into foreground startup rather than prove a net user-visible win.

The next useful measurement is whether repeated yt-dlp process launches remain near ~2 s after the first process has exited. If yes, this is persistent per-process overhead and optimization should focus on avoiding redundant yt-dlp executions in the analyze→download flow where correctness permits. If a second launch is much cheaper, an idle-time/cache-warming strategy can be evaluated separately.

## Validation / reviewer state

- Latest generic CI on the prior `agent-dev` tip passed.
- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- No open PRs or issues were present at the start of this run.
- Recent PR #19 has no submitted reviews or inline review comments. CodeRabbit only posted its automatic-review skip notice; there is no actionable CodeRabbit or `Yashas's code review bot:` feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministically end-to-end validated.
- Emulator timing is useful for bottleneck ranking, not a claim of phone-level absolute latency or a promised speedup.

## Highest-value next step

Measure first-vs-repeat yt-dlp process-launch cost under the same Android instrumentation environment. Only then decide whether to optimize persistent process overhead, idle-time warming, or redundant analyze→download execution; do not tune storage or concurrency based on the current evidence.
