# AGENTS.md

Instructions for coding agents working in this repository (see [agents.md](https://agents.md)).

An Android app that converts a WHOOP data export and writes it into Health Connect, from where the
Google Health app syncs it into the user's account, plus a Go CLI that runs the app on an emulator
for people without an Android phone.

Read [README.md](README.md) first, and do not restate it here: it holds what is imported and how,
the user-facing commands, the sync and verify steps, the layout, and the release process.

## Working here

**The data is personal.** Exports, CSVs and `records.json` are gitignored; never commit, quote or
print them beyond counts. Keep test fixtures synthetic.

**Record IDs are a contract.** Health Connect upserts on `clientRecordId` (`whoop:<kind>:<whoop
timestamp>`). Changing the format duplicates every record in a user's Health Connect. The record
shape `Convert.kt` writes to `records.json` is read by `verify.go`; change both or neither.

**The app talks to the CLI through logcat.** The CLI copies the export into the app's files
directory and starts the activity with the file name in the `zip` extra, plus optional `skip`,
`from` and `until` extras for `--skip`, `--from` and `--until`. Those extras also decide the flow:
with them the app imports straight away, without them it asks which types to import. Tag `Whoogoo`, plain
lines; `done` ends the import and the CLI pulls `files/records.json`, a line starting with
`error:` fails it. Anything else keeps the CLI waiting until its timeout.

**Health Connect validates.** Times are local with the WHOOP cycle's offset; a skin temperature
delta must sit strictly inside its record interval; stage blocks are synthetic because WHOOP
exports totals only. Permissions come from the manifest at runtime (`pm grant` over adb, the app
reads `requestedPermissions`), so the manifest is the only list.

**Run it for real.** `mise run check` (lint, the conversion's JVM tests and the CLI's tests)
proves parsing and matching, nothing else. The end-to-end check is `whoogoo emu --headless` in one
shell and `whoogoo import --apk app/build/outputs/apk/debug/app-debug.apk <export.zip>` in another,
then Health Connect's Data and access screen on the emulator (`adb exec-out screencap -p`). An agent shell has no display, so
Google sign-in, and therefore the account sync and `verify`, need the user at a windowed emulator.

**Releases are automatic.** release-please reads Conventional Commits; the binaries and the APK
are built on merge of its PR. `whoogoo import` downloads the APK of its own version from that release (a `dev` build needs
`--apk`), which returns 404 while the repo is private.

**The signing key is public on purpose.** `app/whoogoo.jks` is a throwaway so every build installs
over the previous one. Do not rotate it.

Report what you verified and what you could not. A claim you did not check is worth less than
saying you did not check it.
