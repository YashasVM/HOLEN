# Agent progress

## Baseline
- Weekly review PR #19 was merged by the user into `main` through `8123df8c`.
- The user then added `db27987a` (release-validation test fix) and `4b46036d` (controlled Android release-branch CI handling) on `main`.
- `agent-dev` was fast-forwarded to `4b46036d` so autonomous work continues from the current user-controlled baseline. No autonomous change was merged to `main` by the agent.

## Completed since weekly review
- None yet. The previous Android reliability/performance batch is now part of the user-reviewed `main` baseline.

## In progress
- Continue the direct-download local-storage/recovery audit from the new baseline. Resume metadata cleanup is corruption-safe; the next target is staging finalization/replacement behavior (`completed.delete()` + `renameTo`) and whether any failure can cause data loss, unsafe reuse, or unnecessary redownloads.
- Keep exact-URL scoping for resumed signed/redirected downloads unless representation equivalence can be proven safely.
- Keep current yt-dlp fragment concurrency/retry policy unchanged unless representative Android/network evidence justifies tuning it.

## Validation / evidence
- PR #19 generic CI passed and Android instrumentation passed. Android verify failed only because `createTempDir` was treated as a compile error; the user corrected that with `db27987a` using `createTempDirectory`.
- Fresh CI for the synchronized `agent-dev` baseline `4b46036d` is running; do not treat the new baseline as fully revalidated on `agent-dev` until those workflows finish.

## Known risks / weekly review
- Direct-download staging finalization currently removes an existing completed staging file and then uses `File.renameTo`; failure is surfaced as a `StorageException`, but replacement semantics are still being audited before changing them.
- Direct-download resume intentionally prefers corruption safety over reuse: strong ETags are preferred, Last-Modified-only state uses a conservative clock margin, and changed signed/redirect targets restart rather than append bytes.
- SAF publication still has a tiny unavoidable process-death window between provider document creation and journaling its returned URI/name; no heuristic recovery is used because matching the wrong user document would be worse than leaving an orphan.
- yt-dlp/extractor compatibility remains dynamic. Runtime updating is preferred over dependency churn unless upstream Android packaging itself materially changes.
