# Contributing to HOLEN

Keep contributions focused, dependency-light, and safe for the edition they
change. Read the applicable edition README before starting.

1. Create a branch for one cohesive change.
2. Do not commit `.env` files, cookies, databases, downloaded media, APK/AAB
   files, signing material, or credentials.
3. Describe every new configuration value in that edition's `.env.example` and
   README. Use non-working placeholders only.
4. Add or update tests for behavior changes and keep docs and licensing claims
   accurate.

## Android checks

From `prod/android/`, run the relevant checks before opening a pull request:

```bash
./gradlew testEmulatorDebugUnitTest lintEmulatorDebug
```

When an emulator/device is available, run the appropriate connected Compose or
instrumentation tests as well. Do not distribute `assembleEmulatorDebug` to
physical Android users; a requested physical-device debug build is
`:app:assembleArm64Debug`.

Use the repository's Kotlin/Compose formatting conventions. Avoid unrelated
formatting churn, and ensure secret-bearing text is never retained in logs,
saved state, test fixtures, or screenshots.

## Other editions

For the root source, run the checks documented in [README.md](README.md). For
the OSS server and private production deployment, use [OSS/README.md](OSS/README.md)
and [prod/README.md](prod/README.md), respectively.

For security-sensitive issues, follow [SECURITY.md](SECURITY.md) instead of
opening a public issue.
