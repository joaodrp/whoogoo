# AGENTS.md

Instructions for coding agents working in this repository (see [agents.md](https://agents.md)).

A Go CLI plus a minimal Android app that load a WHOOP data export into Health Connect on an Android
emulator, from where the Google Health app syncs it into the user's account.

Read [README.md](README.md) first, and do not restate it here: it holds what is imported and how,
the user-facing commands, the sync and verify steps, the layout, and the release process.

## Working here

**The data is personal.** Exports, CSVs and `records.json` are gitignored; never commit, quote or
print them beyond counts. Keep test fixtures synthetic.

**Record IDs are a contract.** Health Connect upserts on `clientRecordId` (`whoop:<kind>:<whoop
timestamp>`). Changing the format duplicates every record in a user's Health Connect. The JSON
shape in `convert.go` is read by `MainActivity.kt`; change both or neither.

**The app talks to the CLI through logcat.** Tag `Whoogoo`, plain lines; `done` ends the import,
a line starting with `error:` fails it. Anything else keeps the CLI waiting until its timeout.

**Health Connect validates.** Times are local with the WHOOP cycle's offset; a skin temperature
delta must sit strictly inside its record interval; stage blocks are synthetic because WHOOP
exports totals only. Permissions come from the manifest at runtime (`pm grant` over adb, the app
reads `requestedPermissions`), so the manifest is the only list.

**Run it for real.** `mise run check` (lint and unit tests, also the pre-commit hook) proves parsing and
matching, nothing else. The end-to-end
check is `whoogoo emu --headless` in one shell and `whoogoo import --apk
app/build/outputs/apk/debug/app-debug.apk <export.zip>` in another, then Health Connect's Data and
access screen on the emulator (`adb exec-out screencap -p`). An agent shell has no display, so
Google sign-in, and therefore the account sync and `verify`, need the user at a windowed emulator.

**Releases are automatic.** release-please reads Conventional Commits; the binaries and the APK
are built on merge of its PR. `whoogoo import` downloads the APK of its own version from that release (a `dev` build needs
`--apk`), which returns 404 while the repo is private.

**The signing key is public on purpose.** `app/whoogoo.jks` is a throwaway so every build installs
over the previous one. Do not rotate it.

Report what you verified and what you could not. A claim you did not check is worth less than
saying you did not check it.
