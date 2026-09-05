# Contributing

Thanks for looking. Small project, narrow purpose: move a WHOOP export into Google Health, and
leave out whatever it cannot carry accurately.

## Getting set up

```sh
mise install        # Go, JDK 21, linters, lefthook
mise run hooks      # git hooks: gitleaks, gofmt, golangci-lint, ktlint, Conventional Commits, tests on push
mise run check      # lint, the app's JVM tests, the CLI's tests. What CI runs
mise run fmt
mise run apk        # builds app/build/outputs/apk/debug/app-debug.apk
mise run dev -- import --apk app/build/outputs/apk/debug/app-debug.apk my_whoop_data.zip
```

You need the Android SDK for anything touching the app. `whoogoo setup` installs and configures it,
and `whoogoo doctor` reports what is missing.

## Layout

The app is Kotlin with Compose and the Health Connect client. The CLI is Go: standard library plus
cobra.

| Path | Role |
|---|---|
| `app/.../Convert.kt` | WHOOP CSVs to records, pure Kotlin so it runs under JVM tests |
| `app/.../HealthConnect.kt` | those records as Health Connect objects, and reading what other apps wrote |
| `app/.../MainActivity.kt`, `Ui.kt` | the import flow and its one screen |
| `app/src/test/` | JVM tests for the conversion |
| `setup.go` | SDK checks, interactive setup, virtual device, emulator |
| `importer.go` | APK download and install, export push, permission grants, log streaming, records pull |
| `verify.go` | Google Health API client (OAuth loopback flow) and the diff |

## Things that will bite you

**The data is personal.** Exports, CSVs and `records.json` are gitignored. Never commit, quote or
print them beyond counts. Test fixtures are synthetic and must stay that way.

**Record IDs are a contract.** Health Connect upserts on `clientRecordId`, formatted
`whoop:<kind>:<whoop timestamp>`. Change that format and every record in every existing user's
Health Connect duplicates. The record shape `Convert.kt` writes is the shape `verify.go` reads:
change both or neither.

**The manifest is the only list of permissions.** The app reads its own `requestedPermissions` at
runtime rather than keeping a second list in code. The app requests write permissions up front; the
CLI grants them over adb for unattended imports. Read permissions exist only for the duplicate
check; the CLI never grants them automatically, so that one always needs a person to tap through.

**Only what survives the trip is imported.** Sleep, skin temperature and calories were each
measured and then deliberately dropped. The README's "Coverage" holds the
reasoning. Do not add them back without new evidence that the numbers land correctly.

**The signing key is not in the repository.** CI signs releases from `ANDROID_KEYSTORE_BASE64` and
`ANDROID_KEYSTORE_PASSWORD`. A build without those environment
variables falls back to the local Android debug key, which is fine for development but means a
locally built APK will not install over a released one: uninstall first.

## The APK is a debug build

`assembleDebug`, deliberately: `adb run-as` only works on a debuggable package, and the CLI needs
it to place the export and read the records back. Two things follow. Anyone with adb access to the
device can read the app's files. And `constant()` in `HealthConnect.kt` resolves exercise types by
reflection, so an `assembleRelease` under R8 would need a keep rule or every workout would fail to
map.

## Testing

`mise run check` proves the parsing and the matching. It does not prove anything about Health
Connect, because that needs a device.

The end-to-end check is `whoogoo emu` in one shell and `whoogoo import` in another, then Health
Connect's "Data and access" screen on the emulator. Signing in to a Google account, and therefore
the account sync and `whoogoo verify`, needs a windowed emulator and a human.

`whoogoo import --dry` reports what would be imported and changes nothing.

The app also understands a `delete` extra naming a file of client record ids to remove instead of
importing. That is a repair path, used to undo an import that should not have happened, and it has
no CLI flag: reach it with `adb shell am start ... --es delete <file>` against a file placed in the
app's own directory. It accepts only ids the export in hand produced.

## Commits and releases

[Conventional Commits](https://www.conventionalcommits.org/), one logical change per commit.

Release-please automates releases: every push to `main` updates a release PR carrying the
version bump and changelog. Merging it tags the release and attaches the binaries for each platform
plus the APK. The CLI downloads the APK matching its own version, so the two ship together and
their log protocol stays in step.
