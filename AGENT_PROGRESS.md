# Agent progress

## Completed
- Created the long-running `agent-dev` branch from `main` and kept `main` untouched.
- Ported the validated Android updater version-normalization fix from former PR #18 onto `agent-dev`.
- Extended safe direct-download resume support to servers that provide a strong `Last-Modified`/`Date` validator but no ETag. Strong ETags remain preferred; weak/malformed ETags still disable resume.
- Enabled the full Android CI workflow on `agent-dev` pushes so Android changes are linted, unit-tested, assembled for emulator/ARM64/ARMv7/universal, and checked for 16 KB native-library compatibility before weekly review.
- The resume/CI batch passed the full Android CI workflow on commit `509b7297ddf316a68dbd481d9aa49984673f51ca`.
- Added bounded automatic retry for direct-file downloads so transient transport errors and HTTP 408/500/502/503/504 can recover without requiring a manual retry. Existing resumable staging is reused between attempts.
- The direct-download retry implementation passed generic CI and the full Android CI matrix on commit `a0f2049cc01611d2eff0311c69a2f0c8982ca0ac`.
- Classified yt-dlp/extractor HTTP failures separately from direct-file failures. HTTP 401/403 now points to expired/authenticated access, 404/410 to unavailable/moved media, 429 to rate limiting, and 5xx to temporary source failure. Explicit bot-challenge text still takes priority over status-only classification.
- The HTTP failure-classification/test repair passed generic CI and full Android CI on commit `289f071c07bf488eef4a4dd9737503aefcadde29`.
- Re-verified yt-dlp/aria2 interrupted-download behavior against current upstream. yt-dlp PR #11698 fixed aria2c resume semantics and was merged before stable 2026.08.19, so no HOLEN-specific downloader fallback or throughput-reducing workaround is justified.

## In progress
- Investigate real Android network-transition behavior only where HOLEN adds value beyond current yt-dlp/aria2 resume and retry handling.

## Validation
- Generic repository CI and full Android CI passed for the Last-Modified resume implementation, bounded direct-download retries, and yt-dlp HTTP failure classification.
- Resume unit coverage includes strong ETag preference, weak ETag rejection, valid Last-Modified fallback, unsafe timestamp rejection, and HTTPS-only persisted resume state.
- Direct retry policy tests cover transient transport/HTTP failures, retry-budget exhaustion, bounded backoff, permanent HTTP failures, rate limiting, TLS failures, malformed redirects, and protocol errors.
- Friendly-failure tests cover yt-dlp-style HTTP 403/404/429/503 messages and verify that explicit bot challenges remain distinct from ordinary rate limiting.
- Current yt-dlp stable `2026.08.19` includes the merged aria2c resume fix (`yt-dlp/yt-dlp#11698`), including persisted aria2 control files for continued downloads and overwrite fallback when a partial transfer cannot be resumed.

## Known risks
- Last-Modified resume is intentionally conservative: it is used only when no ETag is present and the response Date is at least one second later, matching RFC 9110 strong-validator requirements.
- Automatic direct retry is capped at two retries with 1s/2s backoff. HTTP 429 is deliberately not retried automatically because the server may require a longer `Retry-After` interval.
- HTTP status alone cannot prove whether a 401/403 is an expired URL versus account-gated media, so the Android error message deliberately presents both likely actions instead of claiming a single cause.
- Network switching can still expose device/carrier/DNS-specific failures that cannot be proven from repository inspection alone; do not add a second process-level retry loop without a reproducible failure because that risks retry storms and duplicate work.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code commit, then merge only if satisfied.
