# AGENTS.md

Directions for coding agents working in this repository (see [agents.md](https://agents.md)).

[README.md](README.md) says what the tool does and what it refuses to do. [CONTRIBUTING.md](CONTRIBUTING.md)
holds the layout, the build, testing, releases, and the conventions that will bite you: personal
data, the record id contract, the permission manifest, the dropped data types, the signing key.
Read both. Nothing here repeats them.

## Driving the app from a shell

The CLI copies the export into the app's files directory and starts the activity with the file name
in the `zip` extra. Optional extras: `skip`, `from` and `until` narrow what is imported, `delete`
names a file of client record ids to remove instead, and `dry` reports and stops.

**The `zip` extra imports immediately.** There is no confirmation step, and the choosing screen
appears only when a person picks the file through the UI. Launching with `--es zip` to see what the
screen looks like imports the entire export.

Replies come back on the `Whoogoo` logcat tag as plain lines. `done` ends the run and the CLI pulls
`files/records.json`; a line starting with `error:` fails it. Anything else leaves the CLI waiting
until its timeout.

## An import writes to a real account

Health Connect is not a scratch space. What lands there syncs to whichever account is signed in on
the device, and the daily vitals cannot be removed afterwards through the Google Health API. Ask
before importing, and never import invented data onto a device signed in to a real account.

## Proving something works

`mise run check` proves parsing and matching, nothing else. The end to end check is `whoogoo emu
--headless` in one shell and `whoogoo import --apk app/build/outputs/apk/debug/app-debug.apk
<export.zip>` in another, then Health Connect's Data and access screen (`adb exec-out screencap
-p`).

An agent shell has no display, so Google sign-in, and with it the account sync and `verify`, needs
a person at a windowed emulator.

## Traps that have already cost time

**Query the account through `verify`, not by hand.** The Google Health API pages its responses, and
a script that ignores `nextPageToken` returns a partial answer with no error, which reads exactly
like a real absence. `verify.go`'s `listPoints` follows the token.

**`adb devices` is the only reliable check that a device is up.** Matching on process names catches
the agent's own shell.

**Never restart the adb server while an import is running.** The import fails.

Report what you verified and what you could not. A claim you did not check is worth less than
saying you did not check it.
