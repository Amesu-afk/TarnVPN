[Русский](README.md) · **English**

> The Russian README is the primary one and goes into more detail; this is a condensed version.

# TarnVPN

Android VPN client for censored networks: **VLESS + REALITY**, **XHTTP**, and the rest of the
sing-box protocol set, wrapped in a purpose-built interface instead of a config editor.

> **Whose work is what.** The interface, the share-link importer and a set of client-side fixes are
> this project's. Everything they stand on is other people's:
>
> - the app is a fork of [SagerNet/sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) (SFA);
> - the core is [sing-box](https://github.com/SagerNet/sing-box), both by **nekohasekai / SagerNet**;
> - **the `lx` layer — XHTTP, AmneziaWG 2.0, MASQUE, the observability extensions — is
>   [Leadaxe](https://github.com/Leadaxe/sing-box-lx)'s work, not ours.** XHTTP is the transport
>   this app leans on hardest, and it exists here because of that project.
>
> Our own core patches (XHTTP transport pool carried onto lx.15, TLS fragmentation over REALITY,
> `override_destination` on the sniff action, the stream-one path fix) sit in a downstream copy at
> [Amesu-afk/sing-box-lx](https://github.com/Amesu-afk/sing-box-lx), which is what the shipped
> `libbox.aar` is built from.
>
> **None of the projects above endorse this one or are affiliated with it.**

## What it does

- **A server is a row, not a config file.** Paste a `vless://` (or `trojan`, `ss`, `vmess`,
  `hysteria2`, `tuic`, `anytls`) link or a subscription URL, and every server becomes its own
  entry with its own latency reading, measured by TCP handshake outside the tunnel — so the
  numbers exist before you connect to anything.
- **Subscriptions** are first-class: refresh diffs by link, keeps your selection, de-duplicates.
- **Split tunnelling** per application, with include/exclude modes.
- **Kill switch** — applications cannot route around a live tunnel.
- **DNS you can reason about**: DoH presets or your own resolver, and a switch for whether lookups
  travel through the tunnel (the exit's region answers) or straight out (faster, reveals your
  region). Cold lookups for media CDNs are kept off the tunnel so short-video feeds do not stall
  on every clip.
- **Anti-DPI knobs** that the core actually honours: TLS fragmentation over REALITY, QUIC policy,
  MTU, IPv6 strategy, hostname-vs-address destinations.
- Light and dark themes, in-app log viewer, and the full upstream sing-box interface still
  reachable underneath for anything the shell does not cover.

## Install

Grab the **universal** APK from [Releases](https://github.com/Amesu-afk/TarnVPN/releases).

| Build | Android |
|---|---|
| `TarnVPN-<version>-universal.apk` | 6.0+ (API 23) |
| the universal APK whose name contains `legacy-android-5` | 5.0–5.1 (API 21) |

Releases are signed with this project's own key, which is **not** the key in upstream's
`app/release.keystore` (that file ships publicly in the SFA repository, so anything signed with it
could be produced by anyone). A build signed with a different key will not install as an update —
uninstall first, and expect to lose stored profiles.

The app checks its own releases here and can install updates in place; nothing is sent anywhere
else, and update checking can be turned off.

## Build from source

Requirements — the versions matter, and two of them are not negotiable:

- **JDK 17** exactly (gomobile fails on newer JDKs).
- Android SDK with **NDK 28.0.13004108**.
- **Go 1.25+** with `gomobile`, only if you rebuild the core (`make lib_install` in the core repo).

```bash
# 1. the core, if you want your own libbox instead of the committed one
git clone https://github.com/Amesu-afk/sing-box-lx
cd sing-box-lx
go run ./cmd/internal/build_libbox -target android   # emits libbox.aar + libbox-legacy.aar

# 2. the app
cp libbox*.aar clients/android/app/libs/
cd clients/android
./gradlew assembleOtherRelease        # signed release, needs a keystore (below)
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

## Credits

- **[nekohasekai / SagerNet](https://github.com/SagerNet)** — sing-box and SFA, which this is a fork of.
- **[Leadaxe](https://github.com/Leadaxe/sing-box-lx)** — the `lx` core layer: XHTTP, AmneziaWG 2.0,
  MASQUE, the CommandClient observability extensions. Without it this app would have no XHTTP at all.
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
