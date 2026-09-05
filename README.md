# whoogoo

Import your WHOOP vitals and workout history into Google Health. Works on an Android phone, or on
an Android emulator on your computer if you have no phone.

Google Health has no file import, and its cloud API cannot write vitals. Android Health Connect
can, and the Google Health Android app syncs Health Connect data, history included, into your
account. The whoogoo app runs that chain, carrying across the parts of the export that survive the
trip intact and leaving the rest out:

```
WHOOP export zip -> whoogoo app -> Health Connect -> Google Health app -> your account
```

## What gets imported

| WHOOP | Google Health | Note |
|---|---|---|
| Resting heart rate | Resting heart rate | measured during sleep, stamped at wake time |
| Heart rate variability | HRV (RMSSD) | WHOOP HRV is RMSSD |
| Blood oxygen | Oxygen saturation | a night average, stamped at wake time |
| Respiratory rate | Respiratory rate | a night average, stamped at wake time |
| Workout | Exercise session | the WHOOP activity mapped to a Health Connect exercise type, its name kept as the title |

## What is left out, and why

Anything whoogoo cannot carry across truthfully is left out. A wrong number in your health history
is worse than a missing one.

**Sleep.** The export says how many minutes each stage lasted, never when. Health Connect stores
sleep as a session made of staged intervals, so importing it means inventing a timeline: with
stages the shape is fabricated, and without them Google Health reads the whole session as sleep and
records your awake time as zero, overstating some nights by half an hour. Neither is true, so sleep
is not imported.

**Skin temperature.** WHOOP measures an absolute nightly temperature and it survives the trip
intact, but Health Connect stores temperature as a baseline plus a nightly delta, and Google Health
throws our baseline away and recomputes the variation from its own. Until it has enough nights it
prints the absolute temperature as a variation, so every import would open with a reading like
"+33.9 C". Writing the record without a baseline stops the data arriving at all.

**Calories, daily and per workout.** Every device estimates calories its own way, so mixing WHOOP's
numbers into a history that also holds another device's compares things that are not comparable.
WHOOP's per-workout figure also includes the resting energy burned during the workout, which is not
what Health Connect's active calories mean.

**No matching type in Health Connect:** recovery, strain, sleep performance, need, debt, efficiency
and consistency, heart rate zones, max and average heart rate, journal entries. VO2 max is missing
for a different reason: the export does not contain it.

Re-running an import updates records instead of duplicating them.

## On an Android phone

Request your export in the WHOOP app (More -> App settings -> Data export) and wait for the email.

1. Download `whoogoo_<version>.apk` from the
   [latest release](https://github.com/joaodrp/whoogoo/releases/latest) and open it to install
   (Android asks you to allow installs from your browser or Files app).
2. Open Whoogoo, choose the export zip and allow the Health Connect permissions it asks for. The
   app lists what the export holds and how many records of each kind; untick anything you would
   rather leave behind, then import.
3. Follow [Sync to your Google account](#sync-to-your-google-account) below, in Google Health on
   the phone.

## Without a phone: the emulator

The `whoogoo` command line tool sets up an Android emulator and runs the app on it.

### Prerequisites

- Android SDK command-line tools. `whoogoo setup` installs everything else (emulator,
  platform-tools, the Android 17 Play Store image, about 2 GB) and, on macOS with Homebrew, offers to
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
whoogoo emu                             # boots the Pixel 10 / Android 17 emulator; leave it running
whoogoo import my_whoop_data.zip        # in another terminal: installs the app, hands it the export, shows progress
```

`import` takes the whole export by default. `--skip` leaves record types out and `--from` and
`--until` limit the dates, which is how you stop where another device took over:

```sh
whoogoo import --skip exercise --until 2026-05-31 my_whoop_data.zip
```

`whoogoo doctor` is the read-only version of `setup`, and `setup -y` runs the fixes without asking.
Every command has `--help`; `whoogoo completion <shell>` prints shell completions.

If the emulator window is sluggish and its log says "Your GPU drivers may have a bug", the
emulator has fallen back to software rendering. `whoogoo emu --gpu host` uses the real GPU, which
works on most Linux machines despite the warning.

`import` prints progress and ends with `done`. Health Connect on the emulator now holds your data:
search for "Health Connect" in the emulator's Settings and open Data and access.

## Sync to your Google account

On the phone, or in the emulator window:

1. On the emulator: open the Play Store, sign in with your Google account and install Google Health.
   If your account uses passkeys for 2-step verification, choose "Try another way": the emulator has
   no Bluetooth, so a passkey on your phone cannot be used. A backup code or an authenticator app
   works.
2. Open Google Health and sign in.
3. In Google Health: Connections -> Health Connect (or Partner apps -> Set up Health Connect). Allow
   all data types, then under Additional access enable Historical data and background access.
4. Leave the emulator running until it finishes syncing. Old data can take a while to appear.

Menu names follow Google's help pages and may differ slightly between app versions.

Google Health computes Cardio Load and its other scores from first-party devices only, so imported
history feeds the charts and trends without producing scores.

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

## If you already have some of this data

Health Connect keeps one copy per app, so a day that another app already wrote is stored twice,
once from that app and once from whoogoo. Google Health then shows only one of them, the copy it
already had, and the whoogoo copy stays hidden. Days and workouts that nothing else recorded show
up normally. Two ways to avoid the overlap: untick those types in the app, or pass `--until` with
the day before your new device started.

## Start over

In Health Connect: App permissions -> Whoogoo -> See app data, then the delete icon, tick the
types and delete. That removes what whoogoo wrote and nothing else. On the emulator you can instead
delete the virtual device and run `whoogoo emu` again.

Data that has already synced to your Google account is a separate copy, and deleting in Health
Connect does not reliably remove it. Google Health says it does, and for workouts it did within
minutes, but in testing sleep and the daily vitals were still in the account 90 minutes later.
Google Health can delete its own copy directly, per data type and date range, under profile ->
Your data in Google Health -> Deletion options. That one takes effect straight away, and it deletes
every source for those dates rather than only whoogoo's, so pick the range with care.

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
