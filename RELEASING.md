# Releasing

The in-app updater reads GitHub releases of `Amesu-afk/TarnVPN`
(`GitHubUpdateChecker.TARN_RELEASES_REPO`). It is strict, and every requirement it has fails
**silently** — a malformed release is skipped, not reported. Hence this checklist.

## 1. Version

`version.properties` is the single source of truth for both the APK and the metadata asset:

```properties
VERSION_CODE=699          # MUST strictly increase, or installed copies see no update
VERSION_NAME=1.14.0-alpha.47
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

**Do not attach the per-ABI splits.** The updater takes the *first* asset matching "ends with
`.apk`, no `play` in the name, `legacy-android-5` iff the device is pre-M" — with splits present it
can hand an arm64 phone the x86 build.

Mark it **prerelease** for a beta-only rollout; leave it unmarked for the stable channel.

The repository has to stay **public**: the updater uses the unauthenticated GitHub API, and a
private repo would require every user to paste their own token.

## 5. Verify before announcing

Install the *previous* release on a device, then open Settings → check for updates: the new release
must be offered, download, and install over the old one without uninstalling. That single check
covers the metadata format, the asset-picking rule, and the signing key at once.
