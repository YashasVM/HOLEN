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
- The first bundled-aria2 recovery probe in `13a7595b` used HTTP 500 responses and correctly failed Android instrumentation (`33999844695`): aria2 does not retry ordinary HTTP 5xx responses under HOLEN's policy, and yt-dlp reported aria2 exit code 22. This exposed a test-model mistake rather than a production regression; Android verify/build/16 KB checks still passed in that run.
- `fa898de9` corrects the probe to model transport failure instead: after yt-dlp's media probe, the loopback server closes the first two aria2 transfer connections without an HTTP response, then serves the third. This tests the failure class `--max-tries` is intended to recover. Validation is pending.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Evidence / validation
- Diagnostic preservation: generic CI `33973598736`, Android CI `33973598919`, and progress-tip CI `33973633724` passed.
- End-to-end diagnostic classification `a25c8eb7`: generic CI `33976216094` and Android CI `33976216058` passed.
- Aria2 policy integration: generic CI `33997398948` and Android CI `33997399017` passed, including instrumentation and 16 KB native compatibility checks.
- Aria2 audit: current yt-dlp ordinary external downloads invoke the external downloader once; HOLEN routes DASH/m3u8 to the native downloader, so aria2's own retry budget is not multiplied by yt-dlp retries for HOLEN's ordinary HTTP(S) path.
- Aria2 1.37 defines `--max-tries` as total attempts and defaults to 5; yt-dlp `--retries 3` means three retries after the initial attempt. Matching the existing HOLEN budget therefore requires `--max-tries=4`, not 3.
- Aria2's default connection/read timeouts are longer than HOLEN's 20-second native policy. Positive `--retry-wait` changes HTTP 503 retry behavior, so the aligned policy intentionally leaves it unset.
- Failed instrumentation `33999844695` is useful negative evidence: returning HTTP 500 caused bundled aria2 to exit with code 22 rather than retry, confirming the retry probe must use a transport interruption and that HOLEN is not silently broadening HTTP 5xx retries.

## Known risks / weekly review
- The new aria2 timeout policy can fail unusually slow or severely degraded HTTP endpoints sooner than aria2's defaults. This is intentional bounded-failure behavior; loopback probes are reliability checks, not real-network performance benchmarks.
- The aria2 source-contract test protects exact request configuration. The corrected Android probe exercises bundled external-downloader transport retry behavior; partial-byte Range resume still needs separate evidence only if it can be tested deterministically without flaky CI.
- The diagnostic tail is bounded and excludes HOLEN's high-frequency progress marker, but can contain ordinary yt-dlp informational lines before the final error. Specific auth/rate-limit/extractor patterns are classified before generic fallback.
- Direct-download resume favors corruption safety: changed signed/redirect targets restart rather than append bytes.
- SAF publication retains conservative recovery state when provider cleanup fails; rollback and post-completion journal clearing remain best-effort where throwing could destroy or misreport an already published file.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless Android packaging materially changes.

## Next review target
- Validate `fa898de9` in Android instrumentation. If transport retries recover as expected, attempt a deterministic partial-transfer interruption/Range-resume probe only if it can avoid timing-sensitive CI; otherwise close the aria2 task and move to the next evidence-backed Android download bottleneck.
