# Security policy

## Supported versions

Security fixes land in the next release. Only the most recent build receives
them; alpha builds are prereleases, so a fix ships as a new alpha rather than a
patch of an older one.

## Reporting a vulnerability

**Please do not open a public issue.** Use GitHub's private vulnerability
reporting:

<https://github.com/Amesu-afk/TarnVPN/security/advisories/new>

Include, as far as you can:

- app version and version code (Settings → about), Android version and device;
- whether the issue is in the app interface, the share-link importer, or the
  bundled core (`sing-box-lx`);
- reproduction steps and what you expected instead;
- logs or a config **with credentials removed** — never send a working share
  link, subscription URL, UUID, private key or password.

## Scope

In scope:

- traffic leaving the device outside the tunnel: kill-switch/route bypass, DNS
  or IPv6 leaks, traffic from the app itself that bypasses the VPN;
- exposure of credentials or browsing metadata: profiles, generated configs,
  crash/OOM reports, backups, logs, the persisted DNS cache;
- update-channel weaknesses: signature, version or asset-selection checks;
- local exposure: exported components, `FileProvider` paths, loopback listeners
  (including the app-proxy SOCKS listener) usable by another app;
- code execution or privilege escalation reachable through an imported link,
  config, QR code or subscription response.

Out of scope:

- the server side of a VPN provider you connect to;
- whether censorship circumvention is legal in your jurisdiction;
- defects in upstream [`SagerNet/sing-box`](https://github.com/SagerNet/sing-box)
  or `sing-box-for-android` that this fork inherits — report those upstream;
- a specific DPI blocking a profile without a client-side defect (that is a
  question for the provider's configuration).

## What to expect

- acknowledgement within 7 days;
- triage and a fix in the next alpha for confirmed issues;
- credit in the release notes unless you prefer otherwise;
- 90 days of coordinated disclosure by default, or earlier if a fix ships.

There is no bug bounty.
