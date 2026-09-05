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

## In progress
- Aria2 external HTTP failure policy is now implemented on `agent-dev` in `75c65db1` and locked by regression coverage in `a0dcfb0f`: `aria2c:--max-tries=4 --connect-timeout=20 --timeout=20`. Four total aria2 attempts matches yt-dlp's initial attempt plus HOLEN's three configured retries, and the 20-second connect/read limits match HOLEN's socket timeout. `--retry-wait` remains unset so HOLEN does not opt into aria2's separate HTTP 503 retry behavior.
- Generic CI `33997398948` passed for `a0dcfb0f`. Android CI `33997399017` is still running; do not treat the aria2 policy as fully validated until verify/instrumentation finish.
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
- The new aria2 timeout policy can fail unusually slow or severely degraded HTTP endpoints sooner than aria2's defaults. This is intentional bounded-failure behavior, but Android CI does not constitute a real lossy-network benchmark; inspect real-world resume/failure behavior before merging if this area is critical.
- The aria2 regression test currently guards the exact request configuration as a source contract; it protects against accidental policy drift but does not simulate aria2's network retry loop.
- The diagnostic tail is bounded and excludes HOLEN's high-frequency progress marker, but can contain ordinary yt-dlp informational lines before the final error. Specific auth/rate-limit/extractor patterns are classified before generic fallback.
- Direct-download resume favors corruption safety: changed signed/redirect targets restart rather than append bytes.
- SAF publication retains conservative recovery state when provider cleanup fails; rollback and post-completion journal clearing remain best-effort where throwing could destroy or misreport an already published file.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless Android packaging materially changes.

## Next review target
- Finish Android CI for the aria2 policy. If green, run or add the strongest practical Android external-download smoke/failure probe available for timeout, interruption, and resume behavior before considering further retry/concurrency tuning.
