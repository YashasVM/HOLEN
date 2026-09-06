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
- Retired the synthetic aria2 restart/Range probe in `aba7362b` after two instrumentation failures showed the test was asserting unstable downloader internals rather than a reliable HOLEN contract. The proven transport-retry coverage remains; production resume behavior was not weakened.
- Reduced Android cookie-validation allocation pressure in `3433c78c`: oversized/empty private cookie files are rejected before `readBytes()`, and Netscape parsing now streams the line sequence instead of materializing every line into a list. `ae8a277c` covers 5,000 valid cookies plus oversized input; generic CI `34012751328` and Android CI `34012751359` passed.

## In progress
- `016c186f` defers the automatic GitHub app-update request until yt-dlp warmup has completed and HOLEN is idle. If a first interactive analysis is already active at that point, the non-essential automatic check is skipped for that session rather than competing for startup/network resources. Manual Settings checks remain immediate. CI is pending.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Evidence / validation
- Diagnostic preservation: generic CI `33973598736`, Android CI `33973598919`, and progress-tip CI `33973633724` passed.
- End-to-end diagnostic classification `a25c8eb7`: generic CI `33976216094` and Android CI `33976216058` passed.
- Aria2 policy integration: generic CI `33997398948` and Android CI `33997399017` passed, including instrumentation and 16 KB native compatibility checks.
- Aria2 transport retry probe `fa898de9`: generic CI `34002624933` and Android CI `34002624856` passed, confirming bundled aria2 recovers from transport-level connection failures within the configured attempt budget.
- Cleanup after removal of the unstable Range probe is green: CI `34010103142` and Android CI `34010092441` passed.
- Cookie hardening is green: generic CI `34012751328` and Android CI `34012751359` passed. The change is allocation hardening supported by code-path evidence; no wall-clock startup speedup is claimed.
- Current youtubedl-android upstream still documents `0.18.1`, matching HOLEN, so no wrapper dependency bump is justified.
- Android performance guidance recommends keeping non-essential initialization off the startup critical path. HOLEN's prior automatic update request was launched directly from `MainViewModel.init`; `016c186f` removes that overlap without delaying manual update checks.

## Known risks / weekly review
- The aria2 timeout policy can fail unusually slow or severely degraded HTTP endpoints sooner than aria2's defaults. This is intentional bounded-failure behavior; loopback probes are reliability checks, not real-network performance benchmarks.
- Restart-based byte-range continuation is not claimed as CI-validated. HOLEN still relies on yt-dlp/aria2's production continuation behavior; realistic device/network interruption testing is preferable to a brittle emulator probe.
- The diagnostic tail is bounded and excludes HOLEN's high-frequency progress marker, but can contain ordinary yt-dlp informational lines before the final error. Specific auth/rate-limit/extractor patterns are classified before generic fallback.
- Direct-download resume favors corruption safety: changed signed/redirect targets restart rather than append bytes.
- SAF publication retains conservative recovery state when provider cleanup fails; rollback and post-completion journal clearing remain best-effort where throwing could destroy or misreport an already published file.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless Android packaging materially changes.
- Automatic app-update discovery may be postponed until the next launch when the user starts interactive analysis before warmup completes. This is intentional prioritization of the download path; manual update checks remain available.

## Next review target
- Finish generic and Android CI for `016c186f`. If green, inspect the next measurable startup/download duplicate-work candidate rather than tuning fragment concurrency without evidence.
