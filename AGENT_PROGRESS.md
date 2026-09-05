# Agent progress

## Baseline
- `main` remains user-controlled at `4b46036d` (`ci(android): allow controlled release branches`). Autonomous work is only on `agent-dev`.
- Weekly review PR #19 was merged by the user; no autonomous merge, tag, or release to `main` was performed.

## Completed since weekly review
- Hardened Android SAF/staging failure classification and recovery: staging creation, SAF document creation/copy/flush/close, durable pending-publication journaling, and explicit user deletion now report actionable storage/finalization failures. The related generic CI, Android verify, and instrumentation runs passed.
- Improved yt-dlp failure handling for HTTP 429/402 and textual rate limits so users are told to wait instead of immediately retrying.
- Added stale/rotated YouTube-cookie classification and prioritization over generic bot-check advice.
- Preserved a bounded 8 KiB non-progress diagnostic tail from real Android yt-dlp executions so stderr redirected into the callback is not lost before `friendlyFailure()`. End-to-end tests verify stale-cookie, HTTP 429/rate-limit, and account-required diagnostics reach the classifier. Generic and Android CI passed.
- Audited app-level retry behavior: normal yt-dlp failures are not automatically requeued by `DownloadService`; HOLEN already caps yt-dlp transfer and fragment retries at 3 rather than upstream's default 10.
- Aligned aria2 external HTTP failures with HOLEN's native downloader budget in `75c65db1`: `aria2c:--max-tries=4 --connect-timeout=20 --timeout=20`, with no positive `--retry-wait`. Regression coverage in `a0dcfb0f` passed generic CI `33997398948` and Android CI `33997399017`, including instrumentation.

## In progress
- Added Android loopback instrumentation in `13a7595b` that initializes the bundled `libaria2c.so`, lets yt-dlp perform its generic media probe, forces the first two aria2 transfer attempts to return HTTP 500, and asserts recovery on the third transfer attempt within the configured four-attempt budget. Generic CI `33999844652` and Android CI `33999844695` are running; do not treat this as validated until instrumentation finishes.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Evidence / validation
- Diagnostic preservation: generic CI `33973598736`, Android CI `33973598919`, and progress-tip CI `33973633724` passed.
- End-to-end diagnostic classification `a25c8eb7`: generic CI `33976216094` and Android CI `33976216058` passed.
- Aria2 audit: current yt-dlp ordinary external downloads invoke the external downloader once; HOLEN routes DASH/m3u8 to the native downloader, so aria2's own retry budget is not multiplied by yt-dlp retries for HOLEN's ordinary HTTP(S) path.
- Aria2 1.37 defines `--max-tries` as total attempts and defaults to 5; yt-dlp `--retries 3` means three retries after the initial attempt. Matching the existing HOLEN budget therefore requires `--max-tries=4`, not 3.
- Aria2's default connection/read timeouts are longer than HOLEN's 20-second native policy. Positive `--retry-wait` changes HTTP 503 retry behavior, so the aligned policy intentionally leaves it unset.
- Android yt-dlp integrations using `libaria2c.so` accept downloader arguments under the `aria2c:` namespace.

## Known risks / weekly review
- The new aria2 timeout policy can fail unusually slow or severely degraded HTTP endpoints sooner than aria2's defaults. This is intentional bounded-failure behavior; the new loopback retry probe covers deterministic transient HTTP failure recovery but is not a real lossy-network benchmark.
- The aria2 source-contract test protects exact request configuration. The new Android probe exercises the bundled external downloader retry loop, but timeout/interruption resume behavior still needs separate evidence if it can be tested deterministically without flaky CI.
- The diagnostic tail is bounded and excludes HOLEN's high-frequency progress marker, but can contain ordinary yt-dlp informational lines before the final error. Specific auth/rate-limit/extractor patterns are classified before generic fallback.
- Direct-download resume favors corruption safety: changed signed/redirect targets restart rather than append bytes.
- SAF publication retains conservative recovery state when provider cleanup fails; rollback and post-completion journal clearing remain best-effort where throwing could destroy or misreport an already published file.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless Android packaging materially changes.

## Next review target
- Finish CI for the bundled aria2 retry probe. If green, add a deterministic partial-transfer interruption/resume probe only if it can verify real byte-range continuation without introducing timing-dependent CI; otherwise move to the next evidence-backed Android download bottleneck.
