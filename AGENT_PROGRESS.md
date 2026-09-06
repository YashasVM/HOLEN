# Android Agent Progress

## Branch baseline

- Autonomous work stays on `agent-dev`; `main` remains user-controlled and untouched by the maintainer.
- Current baseline inspected before this update: `main` `4b46036d`; `agent-dev` was 92 commits ahead and 0 behind.

## Completed since the last weekly review

- Hardened Android staging/SAF publication and crash recovery without weakening collision safety.
- Improved yt-dlp failure classification, bounded diagnostic-tail handling, retry/recovery behavior, cookie/auth handling, and deferred update traffic.
- Added and validated fragmented-download integrity/retry/concurrency coverage.
- Fixed fallback progress accounting so numbered yt-dlp `.part-FragN` artifacts are not counted as completed progress.
- Prevented numbered yt-dlp fragment temp files from being selected as completed media while preserving legitimate filenames containing `part-Frag` text.
- Direct HTTP downloads now honor bounded `Retry-After` guidance for HTTP 503 while retaining HOLEN's retry cap.
- Added and validated a repeated yt-dlp process-launch timing probe; Android CI `34059630027` passed instrumentation, lint/unit/build, APK assembly, and 16 KB native-library verification.

## Current work: YouTube EJS compatibility

Recent successful Android CI artifacts were compared instead of tuning by guesswork. Across four validated emulator runs (`34037712634`, `34040813804`, `34047099663`, `34050305494`):

- yt-dlp process launch stayed roughly **1.85–2.35 s**.
- cold `YoutubeDL.init` varied roughly **0.90–3.24 s**.
- local extractor work beyond process startup was only **0–365 ms**.
- 64 MiB private-storage write was **22–40 ms**, with fsync **45–77 ms**.
- 64 MiB loopback fresh transfer was **255–330 ms**; resumed-half transfer was **121–135 ms**.
- FFmpeg cold extraction was **1.28–2.49 s**, but production already keeps it off metadata/app-startup critical paths and prewarms it after successful full analysis.

The evidence does **not** support transfer-buffer, fsync, SAF-copy, or fragment-concurrency tuning as the next latency optimization. Production already caches analysis results, performs local yt-dlp initialization through a guarded one-time path, and keeps FFmpeg/aria2 extraction off the metadata critical path.

The proposed analyze-to-download reuse path remains rejected. `--load-info-json` can avoid a fresh extraction, but current upstream has an open format-selection bug in that exact two-stage pattern, and reusing previously extracted direct media URLs can preserve expiring/signed URLs and stale auth/cookie-dependent state.

The EJS investigation has now established the Android packaging gap rather than merely suspecting one. youtubedl-android `0.18.1` adds QuickJS to every request through `--js-runtimes quickjs:<path>`, but its source contains no `yt-dlp-ejs` package integration and no `--remote-components` setup. Current yt-dlp requires both a supported JavaScript runtime and matching EJS solver scripts for full YouTube challenge coverage. yt-dlp documents `--remote-components ejs:github` as the fallback for installations that do not ship the EJS dependency; upstream reports also confirm that once fetched the solver package can be reused from cache.

No production flag was added in this run because enabling a remote component creates a new GitHub/release-assets dependency. Before shipping it, HOLEN should prove Android behavior for first fetch, cached reuse, GitHub-unreachable/offline operation, and both analysis and download paths. A fixed bundled EJS package is not preferred because yt-dlp explicitly requires EJS versions to track the active yt-dlp version and may reject stale packages after an engine update.

## Validation / reviewer state

- Latest production Android change (`dc97caf1`) passed instrumentation, lint/unit/build, release APK assembly, and 16 KB native-library verification in Android CI `34050305494`.
- Repeated-process-launch measurement CI `34059630027` passed; the corresponding generic CI passed as well.
- Generic CI for the previous progress tip `b657eb29` passed in run `34065552037`.
- No open PRs or issues were present at the start of this run.
- Recent PR #19 has no submitted reviews; there is no current maintainer PR targeting `main` and no actionable CodeRabbit or `Yashas's code review bot:` feedback.

## Known risks / review points

- SAF publication still performs a destination-name scan because removing it without a crash-safe provider-renaming strategy can lose publication recovery correctness.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministically end-to-end validated.
- Emulator timing is useful for bottleneck ranking, not a claim of phone-level absolute latency or a promised speedup.
- Reusing analysis output for download is currently rejected because signed media URLs, cookies/auth state, format selection, and extractor behavior can change between analysis and download.
- QuickJS alone does not provide current YouTube challenge coverage when matching EJS scripts are absent. `ejs:github` is the documented fallback, but GitHub/release-assets reachability and first-fetch latency must be validated before enabling it automatically.

## Highest-value next step

Add an Android compatibility probe for `--remote-components ejs:github` that exercises first-fetch and cached reuse separately, then verify failure behavior with the remote source unavailable. Only if those results are acceptable should the option be added to YouTube analysis and download requests; otherwise keep production unchanged and pursue a version-coupled local EJS packaging strategy.
