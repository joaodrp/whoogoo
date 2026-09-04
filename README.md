# whoogoo

Import your WHOOP data export into Google Health. No Android phone needed.

Google Health has no file import, and its cloud API cannot write vitals. Android Health Connect can
write everything WHOOP exports that Google Health understands, and the Google Health Android app
syncs Health Connect data, history included, into your account. whoogoo runs that chain on an
Android emulator on your computer:

```
WHOOP export zip -> whoogoo -> Health Connect (emulator) -> Google Health app -> your account
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

## Prerequisites

- Python 3.10+ (or [mise](https://mise.jdx.dev), which installs it for you)
- Android SDK with the emulator. macOS: `brew install --cask android-commandlinetools`. Otherwise
  install [Android Studio](https://developer.android.com/studio) and open its SDK Manager once, or
  the [command-line tools](https://developer.android.com/studio#command-line-tools-only) plus a JDK
  with `ANDROID_HOME` set. `whoogoo doctor` tells you exactly what is missing and how to get it.
- Linux: access to `/dev/kvm`. macOS: nothing extra.
- A Google account already set up with Google Health

## Install

```sh
mise use -g pipx:whoogoo     # or: pipx install whoogoo, or run without installing: uvx whoogoo
```

## Steps

Request your export in the WHOOP app (More -> App settings -> Data export) and wait for the email.

```sh
whoogoo doctor                          # checks the SDK; prints the sdkmanager command for anything missing
whoogoo emu                             # first run creates a Pixel 10 / Android 16 device, then boots it
whoogoo import my_whoop_data.zip        # in another terminal: converts, installs the importer, loads everything
```

`import` prints progress and ends with `done`. Health Connect on the emulator now holds your data
(Settings -> Health Connect -> Data and access).

## Sync to your Google account

In the emulator window:

1. Open the Play Store and sign in with your Google account.
2. Install Google Health and sign in.
3. In Google Health: Connections -> Health Connect (or Partner apps -> Set up Health Connect). Allow
   all data types, then under Additional access enable Historical data and background access.
4. Leave the emulator running until it finishes syncing. Old data can take a while to appear.

Google Health computes its own sleep score and Cardio Load from first-party devices only, so
imported nights show duration and stages but no score.

## Verify the sync (optional)

`whoogoo verify` reads your account through the Google Health API and diffs it against what was
imported, per type: matched, value differs, missing. One-time setup:

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

The first run opens a browser for consent (read-only scopes) and caches the token under
`~/.config/whoogoo`. Calories are not checked: the API only exposes them as daily rollups.

## Start over

Delete the app's data in Health Connect (Data and access -> App permissions -> Whoogoo -> Delete
app data), or delete the virtual device and run `whoogoo emu` again.

## Development

```sh
mise install        # JDK 21, Python
mise run test
mise run apk        # builds app/build/outputs/apk/debug/app-debug.apk
mise run dev -- import --apk app/build/outputs/apk/debug/app-debug.apk my_whoop_data.zip
```

| Path | Role |
|---|---|
| `whoogoo/convert.py` | stdlib-only converter, WHOOP CSVs to Health Connect records as JSON |
| `whoogoo/cli.py` | doctor, emulator, import, verify; drives the SDK tools and adb |
| `whoogoo/verify.py` | Google Health API client (OAuth loopback flow, stdlib only) and the diff |
| `app/` | minimal Android app that upserts the records into Health Connect |
| `app/whoogoo.jks` | throwaway signing key, committed so every release installs over the previous one |

Releases are automated with release-please from Conventional Commits: every push to `main`
updates a release PR with the version bump and changelog. Merging that PR tags the release, builds
the APK and attaches it (the CLI downloads it from there), and publishes the package to PyPI
through trusted publishing.
