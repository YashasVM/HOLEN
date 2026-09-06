# Agent progress

## Baseline
- `main` remains user-controlled at `4b46036d` (`ci(android): allow controlled release branches`). Autonomous work is only on `agent-dev`.
- Weekly PR #19 was merged by the user. No autonomous merge, tag, or release to `main` has been performed.

## Completed since weekly review
- Hardened Android staging/SAF publication failures and recovery, including durable pending-publication state and actionable storage/finalization errors.
- Improved yt-dlp diagnostics for HTTP 429/402, textual rate limits, stale/rotated YouTube cookies, account-required failures, and HTTP 416 resume-range failures.
- Preserved a bounded 8 KiB yt-dlp diagnostic tail so real stderr reaches Android failure classification.
- Bounded yt-dlp/aria2 retry behavior and aligned aria2 external HTTP attempts/timeouts with HOLEN's direct downloader. A loopback instrumentation probe confirmed bundled aria2 recovers from transport-level connection drops.
- Reduced cookie-validation allocations and removed duplicate cookie parsing/hashing from authenticated metadata analysis while preserving cache identity across cookie state changes.
- Deferred automatic GitHub update traffic until yt-dlp warmup completes and HOLEN is idle, so first interactive analysis is prioritized.
- Corrected fallback progress filtering for real yt-dlp numbered fragment temp names (`.part-Frag49.part` / `.part-Frag1`); final Android CI is green.
- Prevented numbered yt-dlp fragment artifacts from being accepted as completed media when authoritative `after_move:filepath` output is unavailable. The predicate is restricted to numbered yt-dlp suffixes so legitimate titles containing `part-Frag` remain valid. Generic CI `34050305437` and Android CI `34050305494` both passed.
- Direct HTTPS downloads now honor valid bounded `Retry-After` values for HTTP 503. Invalid or >30-second values keep HOLEN's existing bounded exponential fallback, and HTTP 429 behavior is unchanged. Generic CI `34040813801` and Android CI `34040813804` both passed.

## In progress
- Audit first-download latency and staging/disk work using the existing cold-engine, local-transfer, and storage instrumentation before changing runtime behavior. Current code already keeps FFmpeg/aria2 extraction off app startup and metadata critical paths, then best-effort prewarms them only after successful full analysis; no additional startup change is justified without measurement.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.
- SAF destination collision discovery remains intentionally conservative. `publish()` enumerates destination names before `createDocument()` so the durable pre-create journal records a deterministic collision-free name. Removing that scan without another crash-safe identity would create a recovery gap if a `DocumentsProvider` changes the requested display name before HOLEN persists the returned URI.

## Evidence / validation
- Upstream yt-dlp issue logs show native fragmented downloads can leave names such as `*.part-Frag1`, `*.part-Frag1.part`, and numbered `*.part-FragN.part` files. HOLEN's completed-file fallback now rejects those temporary artifacts while regression tests preserve legitimate filenames containing the same text.
- Generic CI `34050305437` and Android CI `34050305494` passed for the narrowed fragment-completion safeguard. The Android run completed successfully on 2026-09-06 after lint/unit tests, release APK assembly, 16 KB verification, and instrumentation.
- Aria2 policy/retry work, cookie hardening, deferred startup update checks, cookie-state cache generation, HTTP 416 guidance, numbered fragment-progress filtering, and HTTP 503 `Retry-After` scheduling all have green generic + Android CI from their final code tips.
- Current youtubedl-android upstream still documents `0.18.1`, matching HOLEN, so no wrapper dependency bump is justified.
- HTTP `Retry-After` is explicitly defined for `503 Service Unavailable` as the server's estimate for when service becomes available again. HOLEN already captured the header in `DirectHttpException`; the completed change only uses it for 503 backoff when it passes the existing 30-second safety bound.
- HOLEN explicitly routes DASH/HLS through yt-dlp's native fragment downloader rather than aria2c. This also avoids the fragmented-manifest aria2c security class fixed by yt-dlp 2026.06.09; ordinary direct media transfers may still use bundled aria2c.
- Existing Android startup instrumentation separately measures cold `YoutubeDL.init`, FFmpeg init, aria2c init, yt-dlp process launch, local extractor overhead, 64 MiB private-storage write/fsync, and fresh/resumed 64 MiB loopback transfers. This is the preferred evidence source before touching startup or transfer buffering.
- No throughput or startup speedup is claimed without device/network measurement.

## Known risks / weekly review
- Aria2/direct HTTP retry budgets intentionally fail severely degraded endpoints sooner than upstream defaults.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministic CI coverage; destructive partial cleanup is avoided without proof that HOLEN-owned staging is stale.
- SAF publication remains conservative around provider failures because throwing or deleting after a successful provider write can misreport or destroy a valid file. The destination-folder name enumeration is retained until a crash-safe alternative exists.
- The fallback staging sampler still traverses the job staging directory after two seconds without extractor progress; its cadence is unchanged because no representative device evidence supports tuning it.
- Automatic app-update discovery can be postponed until the next launch when an interactive request starts first; manual update checks remain immediate.

## Next review target
- Use the existing cold-engine/storage/transfer timing data to identify whether engine extraction, process launch, private-storage writes/fsync, or local transfer buffering is the dominant first-download cost. Change code only if the measured bottleneck has a material, low-regression fix.
