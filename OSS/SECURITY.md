# Security policy

Holen is designed for a trusted network. It intentionally does not provide user accounts or authorization. The supported launcher therefore binds to loopback (`127.0.0.1`) by default. Network-wide binding is an explicit `APP_BIND_HOST=0.0.0.0` or `::` opt-in.

Do not publish a live instance directly to the internet. Put public-facing deployments behind access control, a VPN, firewall rules, and a reverse proxy that you operate.

Please report security issues privately to the repository owner. Do not include credentials, cookies, private URLs, or downloaded files in an issue or pull request.
