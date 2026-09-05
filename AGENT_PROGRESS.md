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

## In progress
- Audit SAF cleanup/delete failure semantics. Publication failure cleanup already keeps the recovery journal whenever provider deletion fails, which is the safe behavior; avoid turning best-effort cleanup into errors that could delete or misclassify an already completed file.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Validation / evidence
- `352ae7df`: generic CI `33943292084` passed and Android CI `33943292094` passed.
- `856e1f58`: generic CI `33945930546` passed; Android verify and instrumentation in run `33945930536` both passed.
- `0034560f`: Android CI run `33948484123` passed. The subsequent `agent-dev` progress-only tip also passed generic CI (`33948497433`).
- `e9dc3141`: generic CI `33953826790` and Android CI `33953826791` passed.
- `6d328e61`: generic CI `33956629862` passed; Android verify and instrumentation in run `33956629863` both passed.
- `9efb6b0e`: progress-only tip passed generic CI `33956653605`; Android CI did not rerun because no Android source changed.

## Known risks / weekly review
- `clearPending` remains best-effort by design: making post-completion journal clearing throw would risk turning an already completed job into cleanup/error handling that may delete a successfully published file. A stale journal can instead be reconciled safely on a later service start.
- Direct-download resume intentionally prefers corruption safety over reuse: strong ETags are preferred, Last-Modified-only state uses a conservative clock margin, and changed signed/redirect targets restart rather than append bytes.
- SAF publication still has a tiny unavoidable process-death window between provider document creation and journaling its returned URI/name; the pre-create journal stores the collision-free destination name so recovery can locate it conservatively.
- The SAF copy path currently checks bytes read from the staging file, not provider-reported persisted size. Do not add immediate size-equality enforcement unless Android provider semantics support it reliably; providers may report unknown or delayed size metadata.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless upstream Android packaging itself materially changes.
