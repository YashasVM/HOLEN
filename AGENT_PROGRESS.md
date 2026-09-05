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
- Rotated/stale yt-dlp account cookies are classified as an authentication-refresh failure instead of generic network or bot-check advice. The classifier recognizes yt-dlp's current YouTube warning that account cookies are no longer valid/likely rotated and prioritizes it over a combined bot-check error. Generic CI `33967431291`, Android CI `33967431296`, and the following progress-tip generic CI `33967446685` passed.
- Real Android yt-dlp downloads now preserve a bounded 8 KiB non-progress tail from the merged callback stream and attach it to failed executions, fixing diagnostic loss caused by `redirectErrorStream=true` plus youtubedl-android's stderr-only failure buffer. Cancellation behavior is preserved. Generic CI `33973598736`, Android CI `33973598919`, and the following progress-tip CI `33973633724` passed.
- End-to-end regression coverage verifies that preserved callback diagnostics reach `friendlyFailure()` for stale-cookie, HTTP 429/rate-limit, and account-required failures. Generic CI `33976216094` and Android CI `33976216058` passed for `a25c8eb7`.
- Retry-path audit found no second automatic job retry after a normal yt-dlp failure: `DownloadService` transitions the job directly to `FAILED`. HOLEN already limits yt-dlp transfer and fragment retries to 3 each rather than upstream's default 10, so no additional retry reduction was made without representative failure evidence.

## In progress
- Audit the aria2 external-downloader path before changing retry/timeout behavior. Upstream yt-dlp's `Aria2cFD` does not translate yt-dlp's generic `retries` or socket-timeout settings into aria2 arguments; aria2 therefore keeps its own retry/timeout defaults for ordinary HTTP(S) transfers unless HOLEN passes explicit `aria2c:` downloader args. Add a targeted failure-path test before deciding whether to align aria2 with HOLEN's 3-retry / 20-second policy.
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
- `a8f6c26a`: Android CI `33967431296` passed; generic CI `33967431291` passed; progress-only tip `24d61053` passed generic CI `33967446685`.
- Diagnostic preservation: generic CI `33973598736`, Android CI `33973598919`, and progress-tip CI `33973633724` passed.
- End-to-end diagnostic classification test tip `a25c8eb7`: generic CI `33976216094` and Android CI `33976216058` passed.
- youtubedl-android 0.18.x execution source starts both stdout/stderr readers, but with `redirectErrorStream=true` stderr is merged into stdout. On non-zero exit it still constructs `YoutubeDLException` only from `errBuffer`. This is direct upstream evidence that HOLEN's prior download invocation could discard detailed yt-dlp failure text even though the callback observed it.
- yt-dlp's upstream FAQ groups HTTP 429 and HTTP 402 under request blocking/overuse guidance. HOLEN's classifier follows that upstream behavior without changing retry counts or bypassing access controls.
- Current yt-dlp YouTube extractor behavior warns when account cookies stop being valid after rotation; the real Android failure path now retains that warning for classification.
- Upstream yt-dlp currently defaults both `--retries` and `--fragment-retries` to 10; HOLEN explicitly uses 3 for each. Normal yt-dlp failures are not requeued by the Android service; only explicit service timeout recovery returns an interrupted job to `QUEUED`.
- Current upstream yt-dlp `Aria2cFD` builds aria2 with its own fixed external-downloader options and maps rate limit, proxy, TLS, timestamps, progress, and resume state, but it does not map yt-dlp's `retries` or socket timeout. aria2 1.37 defaults `--max-tries` to 5 and connection/read timeouts to 60 seconds, so HOLEN's existing `--retries 3` / `--socket-timeout 20` do not by themselves bound aria2 HTTP failure latency.

## Known risks / weekly review
- The diagnostic tail is intentionally bounded and excludes HOLEN's high-frequency progress marker, but it can still contain ordinary yt-dlp informational lines preceding the final error. The user-facing classifier selects known auth/rate-limit/extractor patterns before falling back to generic text.
- aria2 currently has a different effective retry/timeout policy from yt-dlp's native downloader. Do not change it blindly: lowering retries can reduce resilience on flaky mobile networks, while leaving the mismatch can delay failure classification. Validate with controlled connection-drop/timeout cases first.
- `clearPending` remains best-effort by design: making post-completion journal clearing throw would risk turning an already completed job into cleanup/error handling that may delete a successfully published file. A stale journal can instead be reconciled safely on a later service start.
- Publication rollback deletion remains best-effort by design. If provider deletion fails, the pending-publication journal is retained for conservative recovery rather than masking the original finalization failure.
- Direct-download resume intentionally prefers corruption safety over reuse: strong ETags are preferred, Last-Modified-only state uses a conservative clock margin, and changed signed/redirect targets restart rather than append bytes.
- SAF publication still has a tiny unavoidable process-death window between provider document creation and journaling its returned URI/name; the pre-create journal stores the collision-free destination name so recovery can locate it conservatively.
- The SAF copy path currently checks bytes read from the staging file, not provider-reported persisted size. Do not add immediate size-equality enforcement unless Android provider semantics support it reliably; providers may report unknown or delayed size metadata.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless upstream Android packaging itself materially changes.