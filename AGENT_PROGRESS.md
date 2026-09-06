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
- Deferred the automatic GitHub app-update request in `016c186f` until yt-dlp warmup has completed and HOLEN is idle. A first interactive analysis wins over non-essential update traffic; manual Settings checks remain immediate. Generic CI `34015248604` and Android CI `34015248605` passed.
- Removed duplicate authenticated-cookie parsing from metadata analysis: `CookieStore.cacheKey()` now uses an in-process cookie-state generation instead of rereading, validating, and hashing the cookie file before the same request validates it again for `--cookies`. Cache hits remain stable within one auth state and are isolated across save/replace/clear. Generic CI `34020613842` and Android CI `34020613848` passed.
- Added explicit HTTP 416 resume-range guidance in `c1546121`. Extractor failures now explain that the saved byte range no longer matches what the source accepts and direct-file failures explain HOLEN's existing clean-restart behavior. Regression coverage in `a8e48615` passed generic CI `34028932940` and Android CI `34028932905`.

## In progress
- Corrected fallback staging-progress filtering in `ec2b9fc9`: yt-dlp fragment temp files are numbered (`.part-Frag49.part` / `.part-Frag1`), so the previous `endsWith(".part-Frag")` check never matched them. The fallback sampler now excludes the actual numbered fragment temp names instead of potentially counting transient fragment bytes alongside the aggregate staged media. `7e35d35d` adds focused regression coverage; generic and Android CI are running.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.
- Audit the next Android-only bottleneck from production paths; avoid another synthetic aria2 Range probe unless a deterministic user-visible contract can be tested.

## Evidence / validation
- Diagnostic preservation: generic CI `33973598736`, Android CI `33973598919`, and progress-tip CI `33973633724` passed.
- End-to-end diagnostic classification `a25c8eb7`: generic CI `33976216094` and Android CI `33976216058` passed.
- Aria2 policy integration: generic CI `33997398948` and Android CI `33997399017` passed, including instrumentation and 16 KB native compatibility checks.
- Aria2 transport retry probe `fa898de9`: generic CI `34002624933` and Android CI `34002624856` passed, confirming bundled aria2 recovers from transport-level connection failures within the configured attempt budget.
- Cleanup after removal of the unstable Range probe is green: CI `34010103142` and Android CI `34010092441` passed.
- Cookie hardening is green: generic CI `34012751328` and Android CI `34012751359` passed. The change is allocation hardening supported by code-path evidence; no wall-clock startup speedup is claimed.
- Deferred update check is green: generic CI `34015248604` and Android CI `34015248605` passed. This removes non-essential startup/network overlap without claiming a measured latency delta.
- Cookie-state cache generation is green: generic CI `34020613842` and Android CI `34020613848` passed, including instrumentation coverage for stable same-state keys and isolation across cookie save/replace/clear.
- HTTP 416 guidance is green: generic CI `34028932940` and Android CI `34028932905` passed; the progress-tip generic CI `34028950678` also passed.
- Current youtubedl-android upstream still documents `0.18.1`, matching HOLEN, so no wrapper dependency bump is justified.
- Upstream yt-dlp issue #12994 documents a concrete resume failure where HTTP 416 can recur when a Range header survives the retry path; open 2026 issue #16051 shows 416 remains observable in current extractor flows. This supports specific user guidance, but not indiscriminate deletion of HOLEN staging.
- HOLEN's native direct downloader already discards incompatible partial state and retries without Range when a resume response is not a valid 200/206 continuation. That path therefore does not need a new destructive cleanup policy.
- Upstream yt-dlp reports and current 2026 logs show native fragmented downloads use numbered temporary names such as `*.part-Frag49.part` and `*.part-Frag1`; this directly contradicts the old fallback sampler's suffix-only exclusion and supports the narrow filtering fix. No throughput gain is claimed.

## Known risks / weekly review
- The aria2 timeout policy can fail unusually slow or severely degraded HTTP endpoints sooner than aria2's defaults. This is intentional bounded-failure behavior; loopback probes are reliability checks, not real-network performance benchmarks.
- Restart-based byte-range continuation is not claimed as CI-validated. HOLEN still relies on yt-dlp/aria2's production continuation behavior; realistic device/network interruption testing is preferable to a brittle emulator probe.
- The diagnostic tail is bounded and excludes HOLEN's high-frequency progress marker, but can contain ordinary yt-dlp informational lines before the final error. Specific auth/rate-limit/extractor patterns are classified before generic fallback.
- Direct-download resume favors corruption safety: changed signed/redirect targets restart rather than append bytes.
- SAF publication retains conservative recovery state when provider cleanup fails; rollback and post-completion journal clearing remain best-effort where throwing could destroy or misreport an already published file.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless Android packaging materially changes.
- Automatic app-update discovery may be postponed until the next launch when the user starts interactive analysis before warmup completes. This is intentional prioritization of the download path; manual update checks remain available.
- Cookie cache identity now relies on HOLEN's in-process mutation generation instead of hashing bytes. The cookie file lives in app-private `noBackupFilesDir`, all normal runtime mutations use `CookieStore`, and process restart clears the in-memory metadata cache. Self-review rejected a per-request unique-key variant because it would create never-reused cache entries.
- HTTP 416 from yt-dlp/aria2 is classified separately, but autonomous code does not delete partial media on that signal. This is intentional because 416 alone does not prove HOLEN-owned staging is stale.
- The fallback staging sampler still traverses the job staging directory after two seconds without extractor progress. The numbered-fragment fix prevents transient fragment bytes from being misclassified, but scan cadence should not be tuned without device evidence because the fallback exists specifically for extractors that stop emitting progress.

## Next review target
- Finish generic + Android validation for numbered fragment-temp filtering. If green, inspect the next evidence-backed Android startup/download bottleneck; do not tune fragment worker count or fallback scan cadence without representative measurements.
