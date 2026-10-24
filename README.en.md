[Русский](README.md) · **English**

<p align="center">
  <img src="docs/assets/tarnvpn-icon.png" width="96" alt="TarnVPN">
</p>

# TarnVPN

**Android VPN client with VLESS + REALITY, XHTTP and the olcRTC WebRTC tunnel.**
Import a server link or subscription, choose which apps use the VPN, and configure encrypted
DNS in a focused interface available in English and Russian. Bring your own server or subscription.

[![Android 6+](https://img.shields.io/badge/Android-6.0%2B-3ddc84?logo=android&logoColor=white)](#install)
[![Prerelease](https://img.shields.io/badge/release-1.14.0--alpha.68-8b5cf6)](https://github.com/Amesu-afk/TarnVPN/releases/tag/v1.14.0-alpha.68)
[![License GPLv3](https://img.shields.io/badge/license-GPLv3-blue)](LICENSE)

**[Download APK for Android 6+](https://github.com/Amesu-afk/TarnVPN/releases/download/v1.14.0-alpha.68/TarnVPN-1.14.0-alpha.68-universal.apk)** ·
[Android 5](https://github.com/Amesu-afk/TarnVPN/releases/download/v1.14.0-alpha.68/TarnVPN-1.14.0-alpha.68-legacy-android-5-universal.apk) ·
[Release notes](releases/v1.14.0-alpha.68.md) ·
[Report a bug](https://github.com/Amesu-afk/TarnVPN/issues/new/choose)

## Quick start

1. Install the APK for your Android version.
2. Tap **+** and import a server link or HTTPS subscription URL.
3. Select a server, connect, and accept Android's VPN connection request.

## New in alpha.68

- **Network recovery:** a Wi-Fi/mobile handover received during a reset is queued and handled afterward.
- **Targeted XHTTP recovery:** a timeout retires the affected pool member while preserving healthy siblings.
- **XHTTP lifecycle fixes:** bounded HTTP/2 opening, upload/download cleanup, and diagnostics showing where a request waits.

This is a prerelease. Local tests and signed APK checks passed; physical-phone checks for the
reported stalls and updating over the previous version are still pending.

## No bundled VPN servers

This is a client, with no bundled VPN servers, accounts, or payments. It may contact a supplied
server or subscription URL, the selected DNS resolver, and (for olcRTC) the selected WebRTC
provider's infrastructure (Jitsi or Telemost). It also contacts GitHub for update checks, which
can be turned off.

## What it does

- **A server is a row, not a config file.** Paste a `vless://` (or `trojan`, `ss`, `vmess`,
  `hysteria2`, `tuic`, `anytls`) link, a supported `olcrtc://` link, or an HTTPS subscription
  URL. Each server becomes its own entry. Manager v1 QR codes for olcRTC are supported too.
- **Latency depends on the connection type.** Ordinary servers get a TCP handshake measurement
  outside the tunnel before connection. olcRTC has no pre-connect TCP measurement: only the
  connected, active profile shows RTT from its encrypted control channel. The “connect to fastest”
  action uses available measurements, so an olcRTC profile without one is not selected.
- **olcRTC is a separate WebRTC runtime exposing local SOCKS5 to sing-box**, not a sing-box
  protocol. Alpha.68 supports Jitsi with `datachannel` and Telemost with `vp8channel`.
  WB Stream, `seichannel`, and `videochannel` are unavailable in this Android build. A normal
  config reload keeps the active WebRTC session; if its client fully exits, the app attempts to
  reconnect it. The local SOCKS5 port and credentials are created anew for each session.
- **Subscriptions** are first-class: refresh diffs by link, keeps your selection, de-duplicates.
- **Split tunnelling** per application, with include/exclude modes.
- **Block VPN bypass** — keeps apps inside the running VPN service. For protection after the service stops, enable Android Always-on VPN and Block connections without VPN.
- **DNS you can reason about**: DoH presets or your own resolver, and a switch for whether lookups
  travel through the tunnel (the exit's region answers) or straight out (faster, reveals your
  region). Cold lookups for media CDNs are kept off the tunnel so short-video feeds do not stall
  on every clip.
- **Anti-DPI knobs** that the core actually honours: TLS fragmentation over REALITY, QUIC policy,
  MTU, IPv6 strategy, hostname-vs-address destinations.
- Light and dark themes, in-app log viewer, and the full upstream sing-box interface still
  reachable underneath for anything the shell does not cover.

## Recovery after sleep

In alpha.68, the enabled sleep/network recovery setting also handles waking after 60 seconds
with the screen off and deep/light idle on the same Wi-Fi. It resets core network/DNS
connections while retaining the VPN interface. XHTTP closes stale sockets and upload pipes;
DoH retries once only across an observed transport reset within its original deadline.
These client fixes still need verification after sleep on a physical phone.

## Install

Grab the **universal** APK from [Releases](https://github.com/Amesu-afk/TarnVPN/releases).

[Alpha.68](https://github.com/Amesu-afk/TarnVPN/releases/tag/v1.14.0-alpha.68) is a prerelease.
The in-app updater skips prereleases on the Stable track; select the Beta track to receive alpha
versions. olcRTC, recovery after interruption, and updating this APK over a previous one still
need testing on a physical phone.

| Build | Android |
|---|---|
| `TarnVPN-<version>-universal.apk` | 6.0+ (API 23) |
| the universal APK whose name contains `legacy-android-5` | 5.0–5.1 (API 21) |

Releases are signed with this project's own key, which is **not** the key in upstream's
`app/release.keystore` (that file ships publicly in the SFA repository, so anything signed with it
could be produced by anyone). A build signed with a different key will not install as an update —
uninstall first, and expect to lose stored profiles.

The app checks its releases on GitHub and offers installation of available updates. Update checks
can be turned off in settings.

## Build from source

To reproduce the alpha.68 build:

- **OpenJDK 17 or 21**.
- Android SDK with **NDK 28.0.13004108**.
- **Go 1.26.5** with `gomobile`, only if you rebuild the core; alpha.68 used Go 1.26.5.

```bash
# Core and app source for alpha.68
git clone https://github.com/Amesu-afk/sing-box-lx
cd sing-box-lx
git checkout tarnvpn-v1.14.0-alpha.68
git submodule sync --recursive
git submodule update --init --recursive
git -C clients/android checkout v1.14.0-alpha.68

# Build the core: AARs are not stored in this repository
go run ./cmd/internal/build_libbox -target android   # emits libbox.aar + libbox-legacy.aar
cp libbox*.aar clients/android/app/libs/

# App: signed release builds for Android 6+ and Android 5
cd clients/android
./gradlew assembleOtherRelease assembleOtherLegacyRelease --rerun-tasks
./gradlew assembleOtherDebug          # unsigned-ish debug build, no keystore needed
```

Signing is read from `local.properties` (git-ignored):

```properties
KEYSTORE_PASS=…
ALIAS_NAME=…
ALIAS_PASS=…
```

Generate your own `app/tarn-release.keystore` — and back it up together with those three lines.
Losing either means installed copies can never be updated again.

After replacing a `libbox.aar`, build with `--rerun-tasks`: Gradle's incremental build has been
seen to emit a 40% larger, wrong APK from a stale cache.

<details>
<summary>Project origins and core source</summary>

> **Whose work is what.** The interface, the share-link importer and a set of client-side fixes are
> this project's. Everything they stand on is other people's:
>
> - the app is a fork of [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) (SFA);
> - the core is [sing-box](https://github.com/SagerNet/sing-box), both by **nekohasekai / SagerNet**;
> - **the `lx` layer — XHTTP, AmneziaWG 2.0, MASQUE, the observability extensions — is
>   [Leadaxe](https://github.com/Leadaxe/sing-box-lx)'s work, not ours.** XHTTP is the transport
>   this app leans on hardest, and it exists here because of that project.
>
> Our own core patches (the XHTTP transport pool, TLS fragmentation over REALITY,
> `override_destination` on the sniff action, the stream-one path fix, and the mobile olcRTC
> integration) sit in a downstream copy at [Amesu-afk/sing-box-lx](https://github.com/Amesu-afk/sing-box-lx).
> Alpha.68 uses a core based on `v1.14.0-lx.24`; its bundled `libbox.aar` is built from that copy.
> The olcRTC client code is adapted from [Oleglog/OlConnect_manager](https://github.com/Oleglog/OlConnect_manager/tree/c267dd30b0bc).
>
> **None of the projects above endorse this one or are affiliated with it.**


</details>

## Credits

- **[nekohasekai / SagerNet](https://github.com/SagerNet)** — sing-box and SFA, which this is a fork of.
- **[Leadaxe](https://github.com/Leadaxe/sing-box-lx)** — the `lx` core layer: XHTTP, AmneziaWG 2.0,
  MASQUE, the CommandClient observability extensions. Without it this app would have no XHTTP at all.
- **[Oleglog/OlConnect_manager](https://github.com/Oleglog/OlConnect_manager/tree/c267dd30b0bc)** —
  the original olcRTC client code. An
  [adapted client subset](https://github.com/Amesu-afk/sing-box-lx/tree/tarnvpn-lx24-integration/third_party/olcrtc_legacy)
  is included in the core for Android with its license preserved.
- This project — the TarnVPN interface, the importer, and the patches listed at the top.

## License

GPLv3, inherited from upstream and unchanged. The interface layer (`io.nekohasekai.sfa.tarn`),
the share-link importer and the client-side fixes are additions to that work, under the same
terms; everything else belongs to the authors above.

```
Copyright (C) 2022 by nekohasekai <contact-sagernet@sekai.icu>

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.

In addition, no derivative work may use the name or imply association
with this application without prior consent.
```

That last clause is upstream's, and it is why this app carries its own name, its own
`applicationId` (`app.tarnvpn`), its own icon and its own signing key, and why it is not listed
anywhere as SFA. The corresponding source for the core embedded in every release is the
`sing-box-lx` repository linked above — that link is the GPL offer, not a courtesy.
