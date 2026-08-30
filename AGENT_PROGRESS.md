# Agent progress

## Completed
- Created the long-running `agent-dev` branch from `main` and kept `main` untouched.
- Ported the validated Android updater version-normalization fix from former PR #18 onto `agent-dev`.
- Extended safe direct-download resume support to servers that provide a strong `Last-Modified`/`Date` validator but no ETag. Strong ETags remain preferred; weak/malformed ETags still disable resume.
- Enabled the full Android CI workflow on `agent-dev` pushes so branch commits are linted, unit-tested, assembled for emulator/ARM64/ARMv7/universal, and checked for 16 KB native-library compatibility before weekly review.

## In progress
- Validate the new Last-Modified resume path with the first full Android CI run on `agent-dev`.
- Continue Android + yt-dlp reliability/performance work only after the current resume change is green.

## Validation
- Generic repository CI passed on the current Android/CI changes.
- Added unit coverage for strong ETag preference, weak ETag rejection, valid Last-Modified fallback, unsafe timestamp rejection, and HTTPS-only persisted resume state.
- Full Android CI is running on commit `509b7297ddf316a68dbd481d9aa49984673f51ca` and includes the resume implementation and tests.

## Known risks
- Last-Modified resume is intentionally conservative: it is used only when no ETag is present and the response Date is at least one second later, matching RFC 9110 strong-validator requirements.
- `main` remains intentionally untouched by autonomous maintenance.

## Weekly review
- Compare `agent-dev` against `main`, inspect this file and Android CI, then merge only if satisfied.
