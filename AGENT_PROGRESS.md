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
- Corrected fallback progress filtering for real yt-dlp numbered fragment temp names (`.part-Frag49.part` / `.part-Frag1`). The original regression test used Kotlin's deprecated-at-error `createTempDir`; `b0f4bac7` replaced only the test helper with `Files.createTempDirectory`, and Android CI `34037712634` passed.

## In progress
- `17df63a6` makes the Android direct downloader honor a valid bounded `Retry-After` value on HTTP 503 instead of always retrying after HOLEN's 1–2 second exponential delay. HTTP 429 behavior is unchanged; invalid or >30-second 503 values still fall back to the existing bounded backoff.
- `074113b8` adds regression coverage for valid and over-limit 503 `Retry-After` values. Generic CI `34040813801` passed; Android CI `34040813804` is still running.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Evidence / validation
- Aria2 policy/retry work, cookie hardening, deferred startup update checks, cookie-state cache generation, HTTP 416 guidance, and numbered fragment-temp filtering all have green generic + Android CI from their final code tips.
- Current youtubedl-android upstream still documents `0.18.1`, matching HOLEN, so no wrapper dependency bump is justified.
- HTTP `Retry-After` is explicitly defined for `503 Service Unavailable` as the server's estimate for when service becomes available again. HOLEN already captured the header in `DirectHttpException`; the new change only uses it for 503 backoff when it passes the existing 30-second safety bound.
- No throughput or startup speedup is claimed without device/network measurement.

## Known risks / weekly review
- Aria2/direct HTTP retry budgets intentionally fail severely degraded endpoints sooner than upstream defaults.
- Restart-based yt-dlp/aria2 byte-range continuation is not claimed as deterministic CI coverage; destructive partial cleanup is avoided without proof that HOLEN-owned staging is stale.
- SAF publication remains conservative around provider failures because throwing or deleting after a successful provider write can misreport or destroy a valid file.
- The fallback staging sampler still traverses the job staging directory after two seconds without extractor progress; its cadence is unchanged because no representative device evidence supports tuning it.
- Automatic app-update discovery can be postponed until the next launch when an interactive request starts first; manual update checks remain immediate.

## Next review target
- Finish Android CI for `074113b8`. If green, close 503 retry scheduling and inspect the next evidence-backed Android download/startup bottleneck. The SAF destination-folder pre-enumeration remains a candidate, but it should not be removed unless crash-safe collision recovery is preserved when a DocumentsProvider changes the requested display name.
