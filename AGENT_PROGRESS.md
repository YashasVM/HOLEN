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
- Validated bundled aria2 transport recovery in `fa898de9`: after yt-dlp's media probe, a loopback server drops the first two aria2 connections before any HTTP response and the third succeeds. Generic CI `34002624933` and Android CI `34002624856` passed.

## In progress
- `250b44a7` adds a deterministic partial-transfer interruption probe. The server sends one quarter of a 256 KiB response, closes the connection, then requires aria2 to continue with a non-zero HTTP Range request and verifies the finalized file byte-for-byte. Validation is pending.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Evidence / validation
- Diagnostic preservation: generic CI `33973598736`, Android CI `33973598919`, and progress-tip CI `33973633724` passed.
- End-to-end diagnostic classification `a25c8eb7`: generic CI `33976216094` and Android CI `33976216058` passed.
- Aria2 policy integration: generic CI `33997398948` and Android CI `33997399017` passed, including instrumentation and 16 KB native compatibility checks.
- Aria2 transport retry probe `fa898de9`: generic CI `34002624933` and Android CI `34002624856` passed, confirming bundled aria2 recovers from transport-level connection failures within the configured attempt budget.
- Aria2 audit: current yt-dlp ordinary external downloads invoke the external downloader once; HOLEN routes DASH/m3u8 to the native downloader, so aria2's own retry budget is not multiplied by yt-dlp retries for HOLEN's ordinary HTTP(S) path.
- Aria2 1.37 defines `--max-tries` as total attempts and defaults to 5; yt-dlp `--retries 3` means three retries after the initial attempt. Matching the existing HOLEN budget therefore requires `--max-tries=4`, not 3.
- Aria2's default connection/read timeouts are longer than HOLEN's 20-second native policy. Positive `--retry-wait` changes HTTP 503 retry behavior, so the aligned policy intentionally leaves it unset.
- Failed instrumentation `33999844695` remains useful negative evidence: returning HTTP 500 caused bundled aria2 to exit with code 22 rather than retry, confirming HOLEN is not silently broadening ordinary HTTP 5xx retries.

## Known risks / weekly review
- The new aria2 timeout policy can fail unusually slow or severely degraded HTTP endpoints sooner than aria2's defaults. This is intentional bounded-failure behavior; loopback probes are reliability checks, not real-network performance benchmarks.
- The pending Range-resume probe deliberately forces a single aria2 connection to make continuation behavior deterministic; it validates resume correctness, not production throughput or multi-connection behavior.
- The diagnostic tail is bounded and excludes HOLEN's high-frequency progress marker, but can contain ordinary yt-dlp informational lines before the final error. Specific auth/rate-limit/extractor patterns are classified before generic fallback.
- Direct-download resume favors corruption safety: changed signed/redirect targets restart rather than append bytes.
- SAF publication retains conservative recovery state when provider cleanup fails; rollback and post-completion journal clearing remain best-effort where throwing could destroy or misreport an already published file.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless Android packaging materially changes.

## Next review target
- Validate `250b44a7` in Android instrumentation. If the interrupted transfer resumes with a non-zero Range request and exact final bytes, close the aria2 retry/resume task and move to the next evidence-backed Android download bottleneck. If the test exposes real bundled-aria2 resume behavior that differs from assumptions, fix the model or production path rather than hiding the failure.
