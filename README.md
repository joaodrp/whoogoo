# whoogoo

Import your WHOOP data export into Google Health. Works on an Android phone, or on an Android
emulator on your computer if you have no phone.

Google Health has no file import, and its cloud API cannot write vitals. Android Health Connect can
write everything WHOOP exports that Google Health understands, and the Google Health Android app
syncs Health Connect data, history included, into your account. The whoogoo app runs that chain:

```
WHOOP export zip -> whoogoo app -> Health Connect -> Google Health app -> your account
```

## What gets imported

| WHOOP | Google Health | Note |
|---|---|---|
| Sleep onset, wake onset, stage minutes | Sleep session with stages | WHOOP exports stage totals only; stages are written as contiguous blocks with exact totals, so the hypnogram shape is synthetic |
| Respiratory rate | Respiratory rate | stamped at wake time |
| Resting heart rate | Resting heart rate | stamped at wake time |
| Heart rate variability | HRV (RMSSD) | WHOOP HRV is RMSSD |
| Blood oxygen | Oxygen saturation | |
| Skin temp | Skin temperature | baseline = your mean over the export, delta = that night minus baseline |
| Day energy burned | Total calories burned | over the physiological cycle |
| Workout | Exercise session | WHOOP activity mapped to a Health Connect exercise type, name kept as title |
| Workout energy burned | Active calories burned | |

Not imported because Health Connect has no matching type: recovery, strain, sleep
performance/need/debt/efficiency/consistency, HR zones, max/average HR, journal entries.

Re-running an import updates records instead of duplicating them.

## On an Android phone

Request your export in the WHOOP app (More -> App settings -> Data export) and wait for the email.

1. Download `whoogoo_<version>.apk` from the
   [latest release](https://github.com/joaodrp/whoogoo/releases/latest) and open it to install
   (Android asks you to allow installs from your browser or Files app).
2. Open Whoogoo, choose the export zip and allow the Health Connect permissions it asks for.
3. Follow [Sync to your Google account](#sync-to-your-google-account) below, in Google Health on
   the phone.

## Without a phone: the emulator

The `whoogoo` command line tool sets up an Android emulator and runs the app on it.

### Prerequisites

- Android SDK command-line tools. `whoogoo setup` installs everything else (emulator,
  platform-tools, the Android 16 Play Store image, about 2 GB) and, on macOS with Homebrew, offers to
  install the command-line tools themselves. Elsewhere get them from
  [Android Studio](https://developer.android.com/studio) or the
  [command-line tools](https://developer.android.com/studio#command-line-tools-only) download, with
  `ANDROID_HOME` set. A JDK is needed for Google's SDK tools; `setup` offers `mise use -g java@21`
  if mise is present.
- Linux: access to `/dev/kvm`. macOS: nothing extra.
- A Google account already set up with Google Health

### Install

whoogoo is a single static binary for Linux and macOS (x86_64 and arm64).

```sh
mise use -g github:joaodrp/whoogoo
```

or download the archive for your platform from the
[latest release](https://github.com/joaodrp/whoogoo/releases/latest) and put `whoogoo` on your `PATH`.

### Steps

Request your export in the WHOOP app (More -> App settings -> Data export) and wait for the email.

```sh
whoogoo setup                           # checks the SDK, offers to install what is missing, creates the device
whoogoo emu                             # boots the Pixel 10 / Android 16 emulator; leave it running
whoogoo import my_whoop_data.zip        # in another terminal: installs the app, hands it the export, shows progress
```

`whoogoo doctor` is the read-only version of `setup`, and `setup -y` runs the fixes without asking.
Every command has `--help`; `whoogoo completion <shell>` prints shell completions.

`import` prints progress and ends with `done`. Health Connect on the emulator now holds your data:
search for "Health Connect" in the emulator's Settings and open Data and access.

## Sync to your Google account

On the phone, or in the emulator window:

1. On the emulator: open the Play Store, sign in with your Google account and install Google Health.
2. Open Google Health and sign in.
3. In Google Health: Connections -> Health Connect (or Partner apps -> Set up Health Connect). Allow
   all data types, then under Additional access enable Historical data and background access.
4. Leave the emulator running until it finishes syncing. Old data can take a while to appear.

Menu names follow Google's help pages and may differ slightly between app versions.

Google Health computes its own sleep score and Cardio Load from first-party devices only, so
imported nights show duration and stages but no score.

## Verify the sync (optional)

`whoogoo verify` reads your account through the Google Health API and diffs it against what the
last `whoogoo import` loaded, per type: matched, value differs, missing. One-time setup:

1. [Create a Google Cloud project](https://console.cloud.google.com/projectcreate), then
   [enable the Google Health API](https://console.cloud.google.com/apis/library/health.googleapis.com)
   in it.
2. [OAuth consent screen](https://console.cloud.google.com/auth/overview): External, publishing
   status Testing, add your Google account under Audience -> Test users. No verification is needed
   for personal use.
3. [Credentials](https://console.cloud.google.com/apis/credentials) -> Create OAuth client ID ->
   Desktop app. Export the values:

   ```sh
   export GOOGLE_HEALTH_CLIENT_ID=...
   export GOOGLE_HEALTH_CLIENT_SECRET=...
   ```

The first run opens a browser for consent (read-only scopes) and prints where it cached the token.
Calories are not checked: the API only exposes them as daily rollups.

## Start over

Delete the app's data in Health Connect (Data and access -> App permissions -> Whoogoo -> Delete
app data), or on the emulator delete the virtual device and run `whoogoo emu` again.

## Development

```sh
mise install        # Go, JDK 21, linters, lefthook
mise run hooks      # git hooks: gitleaks, gofmt, golangci-lint, ktlint, Conventional Commits, tests on push
mise run check      # lint + the app's JVM tests + the CLI's tests, what CI runs
mise run fmt
mise run apk        # builds app/build/outputs/apk/debug/app-debug.apk
mise run dev -- import --apk app/build/outputs/apk/debug/app-debug.apk my_whoop_data.zip
```

The app is Kotlin with Compose and the Health Connect client; the CLI is Go, standard library
plus cobra for the command line.

| Path | Role |
|---|---|
| `app/src/main/java/dev/joaodrp/whoogoo/Convert.kt` | WHOOP CSVs to Health Connect records, the same shape as `records.json` |
| `app/src/main/java/dev/joaodrp/whoogoo/HealthConnect.kt` | those records as Health Connect objects |
| `app/src/main/java/dev/joaodrp/whoogoo/MainActivity.kt`, `Ui.kt` | the import flow and its one screen |
| `app/src/test/` | JVM tests for the conversion |
| `app/whoogoo.jks` | throwaway signing key, committed so every release installs over the previous one |
| `setup.go` | SDK checks, interactive setup, virtual device, emulator |
| `importer.go` | APK download and install, export push, permission grants, log streaming, records pull |
| `verify.go` | Google Health API client (OAuth loopback flow) and the diff |

Releases are automated with release-please from Conventional Commits: every push to `main`
updates a release PR with the version bump and changelog. Merging that PR tags the release and
attaches the binaries for each platform plus the APK; the CLI downloads the APK of its own version.
