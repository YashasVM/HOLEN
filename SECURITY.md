# Security policy

## Supported public releases

Security fixes are evaluated for the latest non-prerelease Android release and
the current default branch. For the V3 public release, the supported Android
version is 3.3.0 (`android-v3.3.0`). Older releases may receive no fix.

## Report a vulnerability privately

Report vulnerabilities privately to the repository owner through GitHub's
private vulnerability-reporting channel when available. Do not open a public
issue or pull request for an unpatched vulnerability.

Provide a minimal reproduction, affected edition and version, impact, and any
safe logs. Do **not** include credentials, cookies, private URLs, downloaded
files, keystores, signing material, or other secrets. If a secret may have
been exposed, rotate or revoke it before reporting it and state only what was
rotated.

## Cookies and privacy

Android `cookies.txt` support accepts only a pasted Netscape-format file and
stores it in private, no-backup on-device storage. Cookie contents must never
be placed in source control, issues, pull requests, logs, crash reports,
notifications, or screenshots. Cookies can grant account access and are
treated as secrets.

HOLEN Android processes downloads on-device and has no analytics or telemetry.
Cookies only help access content the authenticated account is already
authorized to access; they do not bypass DRM or other access controls.

## Deployment and secret policy

HOLEN OSS is designed for a trusted local environment. Do not expose it
directly to the public internet; use appropriate access controls, a VPN,
firewall rules, and an authenticated reverse proxy.

Never commit `.env` files, `cookies.txt`, `google-services.json`,
`local.properties`, keystores, private keys, service-account JSON, APK/AAB
artifacts, or deployment credentials. Credential examples must contain only
clearly non-working placeholders. Report an accidentally committed secret
privately; do not rewrite shared public history without maintainer approval.
