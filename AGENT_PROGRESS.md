# Agent progress

## Baseline
- Weekly review PR #19 was merged by the user into `main` through `8123df8c`.
- The user then added `db27987a` (release-validation test fix) and `4b46036d` (controlled Android release-branch CI handling) on `main`.
- `agent-dev` was fast-forwarded to `4b46036d` so autonomous work continues from the current user-controlled baseline. No autonomous change was merged to `main` by the agent.

## Completed since weekly review
- Shared Android staging preparation now fails fast with `StorageException` when the private staging directory cannot be created. Unit coverage includes successful creation and a real filesystem path conflict. Generic CI and Android CI passed on `352ae7df`.
- SAF publication now classifies a missing or empty completed staging file as `StorageException` instead of `IllegalArgumentException`. Generic CI, Android verify, and Android instrumentation all passed for `856e1f58`.
- SAF publication now requires the pending-publication journal write to succeed before document creation/copy can continue. A failed synchronous `SharedPreferences.commit()` is classified as `StorageException` instead of silently proceeding without durable recovery state. Generic CI and Android CI passed for `0034560f`.
- SAF output open/write/flush/close and staging-read failures are now classified as storage/finalization failures while preserving coroutine cancellation and already-classified `StorageException`s. Generic CI `33953826790` and Android CI `33953826791` passed for `e9dc3141`.
- SAF `createDocument()` provider failures are now classified as storage/finalization failures with a creation-specific message. Regression coverage on `6d328e61` passed generic CI plus Android verify and instrumentation.
- Explicit user-requested SAF deletion now classifies provider exceptions as `StorageException("The saved file could not be deleted.")` while preserving the provider's boolean-false path and the deliberately best-effort publication rollback behavior. Generic CI and Android CI passed for `b4d13da1`/the following progress tip.
- yt-dlp rate-limit failures are classified separately from generic transport failures. HTTP 429, yt-dlp's documented HTTP 402 anti-abuse response, and status-less messages such as `Too Many Requests` / `rate limit exceeded` tell the user to wait instead of immediately retrying. Generic CI `33964543888` and Android CI `33964529639` passed.

## In progress
- Rotated/stale yt-dlp account cookies are now classified as an authentication-refresh failure instead of generic network or bot-check advice. The classifier recognizes yt-dlp's current YouTube warning that provided account cookies are no longer valid/likely rotated, and gives it priority over a combined `Sign in to confirm you're not a bot` error. Production change is `676e6c94`; focused regression coverage is `a8f6c26a`; CI is pending.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Validation / evidence
- `352ae7df`: generic CI `33943292084` passed and Android CI `33943292094` passed.
- `856e1f58`: generic CI `33945930546` passed; Android verify and instrumentation in run `33945930536` both passed.
- `0034560f`: Android CI run `33948484123` passed. The subsequent `agent-dev` progress-only tip also passed generic CI (`33948497433`).
- `e9dc3141`: generic CI `33953826790` and Android CI `33953826791` passed.
- `6d328e61`: generic CI `33956629862` passed; Android verify and instrumentation in run `33956629863` both passed.
- `9efb6b0e`: progress-only tip passed generic CI `33956653605`; Android CI did not rerun because no Android source changed.
- `b4d13da1`: Android CI `33961872379` passed; the following progress-only tip `46794189` passed generic CI `33961882127`.
- `0a3b1ac6`: Android CI `33964529639` passed; the following `cb4b01c4` progress tip passed generic CI `33964543888`.
- yt-dlp's upstream FAQ groups HTTP 429 and HTTP 402 under request blocking/overuse guidance, and a current August 2026 yt-dlp issue still shows HTTP 429 as an active real-world failure mode. HOLEN's classifier follows that upstream behavior without changing retry counts or bypassing access controls.
- Current yt-dlp YouTube extractor code explicitly warns when account cookies stop being valid after rotation, and 2026 upstream reports show that this can happen during real download sessions. HOLEN now recognizes that exact failure mode rather than treating refreshable authentication state as a generic retry problem.

## Known risks / weekly review
- `clearPending` remains best-effort by design: making post-completion journal clearing throw would risk turning an already completed job into cleanup/error handling that may delete a successfully published file. A stale journal can instead be reconciled safely on a later service start.
- Publication rollback deletion remains best-effort by design. If provider deletion fails, the pending-publication journal is retained for conservative recovery rather than masking the original finalization failure.
- Direct-download resume intentionally prefers corruption safety over reuse: strong ETags are preferred, Last-Modified-only state uses a conservative clock margin, and changed signed/redirect targets restart rather than append bytes.
- SAF publication still has a tiny unavoidable process-death window between provider document creation and journaling its returned URI/name; the pre-create journal stores the collision-free destination name so recovery can locate it conservatively.
- The SAF copy path currently checks bytes read from the staging file, not provider-reported persisted size. Do not add immediate size-equality enforcement unless Android provider semantics support it reliably; providers may report unknown or delayed size metadata.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless upstream Android packaging itself materially changes.
