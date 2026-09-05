# AGENTS.md

Instructions for coding agents working in this repository (see [agents.md](https://agents.md)).

An Android app that converts a WHOOP data export and writes it into Health Connect, from where the
Google Health app syncs it into the user's account, plus a Go CLI that runs the app on an emulator
for people without an Android phone.

Read [README.md](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md) first, and do not restate them
here. The README holds what is imported and why the rest is not, plus the user-facing commands.
CONTRIBUTING holds the layout, the build, the testing story and the release process. What follows
is only what neither of those says.

## Working here

**The data is personal.** Exports, CSVs and `records.json` are gitignored; never commit, quote or
print them beyond counts. Keep test fixtures synthetic.

**Record IDs are a contract.** Health Connect upserts on `clientRecordId` (`whoop:<kind>:<whoop
timestamp>`). Changing the format duplicates every record in a user's Health Connect. The record
shape `Convert.kt` writes to `records.json` is read by `verify.go`; change both or neither.

**The app talks to the CLI through logcat.** The CLI copies the export into the app's files
directory and starts the activity with the file name in the `zip` extra. The presence of `zip`
decides the flow: with it the app acts straight away, without it a person picks the file and
chooses on screen. Optional extras: `skip`, `from` and `until` narrow what is imported; `delete`
names a file of client record ids to remove instead; `dry` reports and stops.

Replies come back on the `Whoogoo` tag as plain lines. `done` ends the run and the CLI pulls
`files/records.json`; a line starting with `error:` fails it. Anything else keeps the CLI waiting
until its timeout.

**Health Connect validates.** Times are local with the WHOOP cycle's offset. Permissions come from
the manifest at runtime, so the manifest is the only list; the CLI grants the write ones over adb
and never the read ones, which keeps the duplicate check an explicit choice.

**Only what survives the trip is imported.** Sleep, skin temperature and calories were all measured
and then deliberately dropped; the README's "Left out" holds the reasoning and the
evidence. Do not add them back without new evidence that the numbers land correctly.

**Run it for real.** `mise run check` proves parsing and matching, nothing else. The end-to-end
check is `whoogoo emu --headless` in one shell and `whoogoo import --apk
app/build/outputs/apk/debug/app-debug.apk <export.zip>` in another, then Health Connect's Data and
access screen on the emulator (`adb exec-out screencap -p`). An agent shell has no display, so
Google sign-in, and therefore the account sync and `verify`, need the user at a windowed emulator.

**Query the account through `verify`, not by hand.** The Google Health API pages its responses, and
an ad-hoc script that ignores `nextPageToken` returns a partial answer with no error, which reads
exactly like a real absence. `verify.go`'s `listPoints` follows the token; scratch scripts written
in a hurry have not.

**Releases are automatic.** `whoogoo import` downloads the APK of its own version from the
release, so a `dev` build needs `--apk`.

**The signing key lives in CI secrets.** `WHOOGOO_KEYSTORE` and `WHOOGOO_KEYSTORE_PASSWORD` switch
the app's signing config; without them Gradle uses the local debug key. Never commit a keystore.

Report what you verified and what you could not. A claim you did not check is worth less than
saying you did not check it.
