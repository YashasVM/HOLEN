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
- Measured the current ARM64 release APK rather than guessing at package-size bottlenecks. The APK is 63,698,918 bytes (~60.7 MiB); the dominant payloads are `libffmpeg.zip.so` 35,624,931 bytes, `libpython.zip.so` 14,305,904 bytes, and aria2 (`libaria2c.zip.so` + `libaria2c.so`) 6,842,837 bytes. These three required media-engine components account for ~89% of the APK, so ordinary R8/resource cleanup cannot produce a material size reduction.
- Removed FFmpeg extraction from the background metadata warm-up path. Fresh installs now prepare only Python/yt-dlp before analysis; FFmpeg remains lazily initialized at download time. This avoids letting the 35.6 MB FFmpeg payload hold the shared initialization mutex while the first metadata request waits for media tools it does not use.
- Fixed same-process engine-reset recovery. After a manual bundled-engine reset or a failed stable-engine update clears extracted runtime files, analyze/download now fail with an explicit restart-required error instead of calling upstream singleton `init()` methods that may already believe they are initialized. The aria2 extraction-version marker is also cleared with Python/FFmpeg/yt-dlp markers. First-initialization fallback remains restart-free.
- The engine-reset guard passed generic CI plus full Android CI (lint, unit tests, APK builds, and 16 KB native-library validation) on commit `771f6097f0eb9deca62370014dffa4c165887149`.
- Hardened yt-dlp downloader selection for fragmented manifests in `41ae0d4e7b99d0a507f4650ffd554217e8eb0a4a`: aria2c remains the default external downloader for ordinary transfers, while `dash,m3u8` are explicitly forced to yt-dlp's native downloader. This matches yt-dlp's workaround for GHSA-vx4q-3cr2-7cg2 / CVE-2026-50574 and protects an older bundled engine even before the stable updater runs.
- The manifest-downloader hardening passed generic CI and full Android CI, including lint, unit tests, APK assembly, and 16 KB native-library validation, on Android CI run `33406133219`.
- Fixed restart-required engine-reset failures being misclassified as generic network failures. Download jobs now preserve the actionable “close and reopen HOLEN” guidance, with a regression test covering the `IOException` path in commit `d5e18bf48804c317eca6eeab085bc8cb7ea54144`.
- The restart-guidance classification fix passed generic CI and full Android CI on commit `d5e18bf48804c317eca6eeab085bc8cb7ea54144`; Android CI run `33417196473` completed successfully.

## In progress
- Investigate first yt-dlp-managed download startup latency. Do not prewarm or parallelize Python/FFmpeg/aria2 extraction without device-side timing or another defensible structural benefit.

## Validation
- Generic repository CI and full Android CI passed for the Last-Modified resume implementation, bounded direct-download retries, yt-dlp HTTP failure classification, lazy-FFmpeg initialization, engine-reset guard, manifest-downloader hardening, and restart-guidance classification.
- Resume unit coverage includes strong ETag preference, weak ETag rejection, valid Last-Modified fallback, unsafe timestamp rejection, and HTTPS-only persisted resume state.
- Direct retry policy tests cover transient transport/HTTP failures, retry-budget exhaustion, bounded backoff, permanent HTTP failures, rate limiting, TLS failures, malformed redirects, and protocol errors.
- Friendly-failure tests cover yt-dlp-style HTTP 403/404/429/503 messages, verify that explicit bot challenges remain distinct from ordinary rate limiting, and verify that an engine-reset restart requirement cannot fall through to generic network-retry guidance.
- Current yt-dlp stable `2026.08.19` includes the merged aria2c resume fix (`yt-dlp/yt-dlp#11698`), including persisted aria2 control files for continued downloads and overwrite fallback when a partial transfer cannot be resumed.
- Latest measured Android ARM64 test artifact came from successful Android CI run 33343043276 on commit `289f071c07bf488eef4a4dd9737503aefcadde29`; its release APK measured 63,698,918 bytes.
- The engine-reset fix is intentionally narrow: destructive runtime clearing marks the current process unusable until restart, while recovery from a failed first initialization still retries once without setting the restart guard.
- yt-dlp's documented downloader syntax supports a default downloader plus protocol-specific overrides; the security advisory explicitly recommends `--downloader dash,m3u8:native` for users unable to immediately upgrade an affected engine.

## Known risks
- Last-Modified resume is intentionally conservative: it is used only when no ETag is present and the response Date is at least one second later, matching RFC 9110 strong-validator requirements.
- Automatic direct retry is capped at two retries with 1s/2s backoff. HTTP 429 is deliberately not retried automatically because the server may require a longer `Retry-After` interval.
- HTTP status alone cannot prove whether a 401/403 is an expired URL versus account-gated media, so the Android error message deliberately presents both likely actions instead of claiming a single cause.
- Network switching can still expose device/carrier/DNS-specific failures that cannot be proven from repository inspection alone; do not add a second process-level retry loop without a reproducible failure because that risks retry storms and duplicate work.
- Deferring FFmpeg trades fresh-install metadata latency for one-time initialization immediately before the first yt-dlp-managed download. No numeric speedup is claimed until a device-side cold-install benchmark is available; the change only removes unnecessary FFmpeg work from the metadata critical path.
- The APK-size bottleneck is structural: removing FFmpeg breaks common split-stream merging/post-processing, removing aria2 trades away the current fast external downloader, and downloading these engines at first run would add network/bootstrap/security/update complexity and weaken offline reliability. Do not make that trade without a concrete product decision and measured benefit.
- Engine reset/update-failure recovery now deliberately requires an app restart because youtubedl-android's process-local singleton initialization flags cannot be safely reset by HOLEN after deleting extracted runtime files.
- The manifest safety override intentionally gives DASH/HLS transfers to yt-dlp's native fragment downloader, so those protocols do not receive aria2c's transfer behavior; this is the upstream-recommended security trade-off and ordinary HTTP transfers still use aria2c.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and the latest Android CI for the most recent Android code commit, then merge only if satisfied.
