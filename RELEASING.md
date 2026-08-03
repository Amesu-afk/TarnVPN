# Releasing

The in-app updater reads GitHub releases of `Amesu-afk/TarnVPN`
(`GitHubUpdateChecker.TARN_RELEASES_REPO`). It is strict, and every requirement it has fails
**silently** — a malformed release is skipped, not reported. Hence this checklist.

## 1. Version

`version.properties` is the single source of truth for both the APK and the metadata asset:

```properties
VERSION_CODE=700          # MUST strictly increase, or installed copies see no update
VERSION_NAME=1.14.0-alpha.48
```

`VERSION_NAME` decides the update **channel** of the build being released
(`Settings.updateTrack`): a name containing `-alpha`/`-beta`/`-rc` puts that build on the beta
track, anything else on stable. Stable-track builds ignore prereleases entirely.

## 2. Build

```bash
cd clients/android
./gradlew assembleOtherRelease          # Android 6+
./gradlew assembleOtherLegacyRelease    # Android 5 — filename gets `legacy-android-5`
```

Both need `KEYSTORE_PASS` / `ALIAS_NAME` / `ALIAS_PASS` in `local.properties` and
`app/tarn-release.keystore`. Signing with anything else produces a build that cannot install over
an existing TarnVPN.

If `app/libs/libbox.aar` was replaced since the last build, add `--rerun-tasks`: Gradle's
incremental build has produced a wrong, much larger APK from a stale cache.

## 3. Metadata asset

Generate it from `version.properties` rather than typing it — a mismatch between the JSON and the
APK is exactly the failure that looks like "the updater is broken":

```bash
cd clients/android
CODE=$(grep '^VERSION_CODE=' version.properties | cut -d= -f2)
NAME=$(grep '^VERSION_NAME=' version.properties | cut -d= -f2)
printf '{"version_code": %s, "version_name": "%s"}\n' "$CODE" "$NAME" \
  > app/build/outputs/apk/other/release/tarn-version-metadata.json
```

## 4. The GitHub release

Attach exactly these:

- `TarnVPN-<version>-universal.apk`
- the `legacy-android-5` universal APK (only if you built the legacy flavour)
- `tarn-version-metadata.json`

**Do not attach the per-ABI splits — not yet.** See the next section: this is a transition, and
attaching them today breaks the copies people already have.

### Why not the splits, and when that changes

Up to and including `1.14.0-alpha.47` the updater took the *first* asset matching "ends with
`.apk`, no `play` in the name, `legacy-android-5` iff the device is pre-M". GitHub returns assets
ordered by name, and `TarnVPN-<version>-arm64-v8a.apk` sorts before `…-universal.apk` — so
`v1.14.0-alpha.47`, which did attach the splits, offered the **arm64 build to every device**,
including armeabi-v7a and x86 phones where it fails to install with
`INSTALL_FAILED_NO_MATCHING_ABIS`. Attaching splits is what made the ordering matter; the ordering
itself cannot be controlled, since upload order does not affect it.

From `1.14.0-alpha.48` the updater matches the device's own ABIs first
(`GitHubUpdateChecker.pickApkAsset`, covered by `GitHubUpdateCheckerApkPickTest`) and only falls
back to universal. But the rule that matters for a given release is the one compiled into the copy
**already installed**, not the one in this tree. So:

- **while any `≤ 1.14.0-alpha.47` install may still be out there**: universal only, as above;
- **once the installed base is past it**: attach the splits as well, and phones download ~31 MB
  instead of ~105 MB. Keep the universal APK attached in that case too — it is the fallback for
  any ABI without a split.

Mark it **prerelease** for a beta-only rollout; leave it unmarked for the stable channel.

The repository has to stay **public**: the updater uses the unauthenticated GitHub API, and a
private repo would require every user to paste their own token.

## 5. Verify before announcing

Install the *previous* release on a device, then open Settings → check for updates: the new release
must be offered, download, and install over the old one without uninstalling. That single check
covers the metadata format, the asset-picking rule, and the signing key at once.

Then verify the path most users actually take, which is not that button: connect the VPN and wait.
From `1.14.0-alpha.48` the app checks on app start *and* once the tunnel comes up
(`UpdateChecks.runIfDue`, called from `TarnShell`), and the dialog is hosted in the TarnVPN shell
rather than only in the sing-box interface. The post-connect check is the one that matters —
`api.github.com` is often unreachable until the tunnel is up. Checks are throttled to one per
6 hours; to re-test immediately, use the settings button, which forces one.
