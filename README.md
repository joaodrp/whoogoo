# whoop-to-google-health

Import your WHOOP data export into Google Health, no Android phone required.

Google Health has no file import, and its cloud API cannot write vitals. Android Health Connect can
write everything WHOOP exports that Google Health understands, and the Google Health Android app
syncs Health Connect data, history included, into your account. This repo runs that chain on an
Android emulator:

```
WHOOP export zip -> whoop2hc.py -> records.json -> importer app -> Health Connect -> Google Health app -> your account
```

## What gets imported

| WHOOP | Health Connect record | Note |
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

Records carry a deterministic client ID, so re-running the import updates instead of duplicating.

## Prerequisites

- [mise](https://mise.jdx.dev) (installs the pinned JDK and Python)
- Android SDK command-line tools with `ANDROID_HOME` set and `$ANDROID_HOME/cmdline-tools/latest/bin`
  plus `$ANDROID_HOME/platform-tools` on `PATH`. Android Studio's SDK Manager installs them, or
  download them from https://developer.android.com/studio#command-line-tools-only.
- Linux: `/dev/kvm` access for the emulator. macOS: nothing extra.
- A Google account already set up with Google Health (the one your Fitbit is on)

## Steps

```sh
git clone https://github.com/joaodrp/whoop-to-google-health && cd whoop-to-google-health
mise install            # JDK 21, Python 3
mise run sdk            # Android platform, build tools, emulator, Play Store system image
mise run avd            # create the virtual device
mise run emu            # boot it; leave this running
```

Request your export in the WHOOP app (More -> App settings -> Data export) and wait for the email.

```sh
mise run convert ~/Downloads/my_whoop_data_2026_09_04.zip   # writes records.json, prints counts
mise run import                                              # builds, installs, loads, imports
```

The import task streams the app log and ends with `done`. Health Connect on the emulator now holds
your data (Settings -> Health Connect -> Data and access).

## Sync to your Google account

In the emulator window:

1. Open the Play Store and sign in with your Google account.
2. Install Google Health and sign in.
3. In Google Health: Connections -> Health Connect (or Partner apps -> Set up Health Connect). Allow
   all data types, then under Additional access enable Historical data and background access.
4. Leave the emulator running until the app finishes syncing. Old data can take a while to appear.

Google Health computes its own sleep score and Cardio Load from first-party devices only, so
imported nights show duration and stages but no score.

## Re-running

`mise run convert` and `mise run import` are idempotent. To wipe and start over, delete the app's
data in Health Connect (Data and access -> App permissions -> WHOOP Import -> Delete app data), or
`mise run avd` to recreate the device.

## Layout

| Path | Role |
|---|---|
| `whoop2hc.py` | stdlib-only converter, WHOOP CSVs to Health Connect records as JSON |
| `app/` | minimal Android app that upserts `records.json` into Health Connect |
| `mise.toml` | pinned tools and the tasks above |
