"""whoogoo command line: doctor, emu, convert, import, verify."""
import argparse
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

from . import convert, verify

APP = "dev.joaodrp.whoogoo"
AVD = "whoogoo"
ABI = "arm64-v8a" if platform.machine() in ("arm64", "aarch64") else "x86_64"
IMAGE = f"system-images;android-36;google_apis_playstore;{ABI}"
APK_URL = "https://github.com/joaodrp/whoogoo/releases/latest/download/whoogoo.apk"
CACHE = Path.home() / ".cache" / "whoogoo"
RECORDS = CACHE / "records.json"
TOOLS = {
    "adb": "platform-tools/adb",
    "emulator": "emulator/emulator",
    "sdkmanager": "cmdline-tools/latest/bin/sdkmanager",
    "avdmanager": "cmdline-tools/latest/bin/avdmanager",
}
# Mirrors the <uses-permission> list in app/src/main/AndroidManifest.xml.
LOG_LINE = re.compile(r"Whoogoo\s*: (.*)")
PERMISSIONS = [f"android.permission.health.WRITE_{p}" for p in (
    "SLEEP", "EXERCISE", "ACTIVE_CALORIES_BURNED", "TOTAL_CALORIES_BURNED", "RESTING_HEART_RATE",
    "HEART_RATE_VARIABILITY", "OXYGEN_SATURATION", "RESPIRATORY_RATE", "SKIN_TEMPERATURE")]


def sdk_root():
    candidates = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
                  Path.home() / "Android" / "Sdk", Path.home() / "Library" / "Android" / "sdk"]
    return next((Path(c) for c in candidates if c and Path(c).is_dir()), None)


def tool(name):
    root = sdk_root()
    path = root / TOOLS[name] if root else None
    return str(path) if path and path.exists() else shutil.which(name)


def adb(*args, **kw):
    return subprocess.run([tool("adb"), *args], check=True, text=True, capture_output=True, **kw).stdout


def doctor(_args=None):
    root = sdk_root()
    checks = [("Android SDK", root, "install Android Studio or the command-line tools and set ANDROID_HOME")]
    packages = {"adb": "platform-tools", "emulator": "emulator", "sdkmanager": "cmdline-tools;latest", "avdmanager": "cmdline-tools;latest"}
    for name in TOOLS:
        checks.append((name, tool(name), f'sdkmanager "{packages[name]}"'))
    image = root and (root / "system-images" / "android-36" / "google_apis_playstore" / ABI).is_dir()
    checks.append(("Android 16 Play Store image", image, f'sdkmanager "{IMAGE}"'))
    checks.append(("java (needed by sdkmanager/avdmanager)", shutil.which("java"), "install a JDK, e.g. mise use -g java@21"))
    if sys.platform == "linux":
        checks.append(("/dev/kvm", os.access("/dev/kvm", os.R_OK | os.W_OK), "add yourself to the kvm group"))
    ok = True
    for label, good, hint in checks:
        print(f"{'ok     ' if good else 'MISSING'}  {label}" + ("" if good else f"  ->  {hint}"))
        ok &= bool(good)
    if not ok:
        sys.exit(1)


def wait_for_device():
    adb("wait-for-device")
    for _ in range(120):
        if adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            return
        time.sleep(5)
    sys.exit("device did not finish booting")


def emu(args):
    doctor()
    avd_home = Path(os.environ.get("ANDROID_AVD_HOME", Path.home() / ".android" / "avd"))
    if not (avd_home / f"{AVD}.avd").is_dir():
        print(f"creating virtual device {AVD}")
        cmd = [tool("avdmanager"), "create", "avd", "--force", "-n", AVD, "-k", IMAGE]
        if subprocess.run(cmd + ["-d", "pixel_10"], input="no\n", text=True, capture_output=True).returncode:
            subprocess.run(cmd, input="no\n", text=True, check=True)
    cmd = [tool("emulator"), "-avd", AVD, "-no-snapshot-load"]
    if args.headless:
        cmd += ["-no-window", "-gpu", "swiftshader_indirect"]
    print("booting emulator; leave this running and use another terminal for `whoogoo import`")
    subprocess.run(cmd, check=True)


def convert_(args):
    counts = convert.write(args.export, args.out)
    print(f"{sum(counts.values())} records -> {args.out} {counts}")


def download_apk():
    CACHE.mkdir(parents=True, exist_ok=True)
    apk = CACHE / "whoogoo.apk"
    print(f"downloading {APK_URL}")
    urllib.request.urlretrieve(APK_URL, apk)
    return apk


def import_(args):
    src = Path(args.source)
    if src.suffix == ".json":
        records = src
    else:
        records = RECORDS
        counts = convert.write(src, records)
        print(f"{sum(counts.values())} records {counts}")
    apk = Path(args.apk) if args.apk else download_apk()
    print("waiting for the emulator")
    wait_for_device()
    try:
        adb("install", "-r", str(apk))
    except subprocess.CalledProcessError as e:
        if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" not in e.stderr + e.stdout:
            raise
        adb("uninstall", APP)
        adb("install", str(apk))
    adb("push", str(records), "/data/local/tmp/records.json")
    adb("shell", "run-as", APP, "mkdir", "-p", "files")
    adb("shell", "run-as", APP, "cp", "/data/local/tmp/records.json", "files/records.json")
    for p in PERMISSIONS:
        adb("shell", "pm", "grant", APP, p)
    adb("logcat", "-c")
    adb("shell", "am", "start", "-n", f"{APP}/.MainActivity")
    seen = 0
    for _ in range(300):
        lines = [m.group(1) for m in map(LOG_LINE.search, adb("logcat", "-d", "-s", "Whoogoo").splitlines()) if m]
        for l in lines[seen:]:
            print(l)
        seen = len(lines)
        if lines and lines[-1].split()[0] in ("done", "insert", "denied", "missing", "bad"):
            sys.exit(0 if lines[-1] == "done" else 1)
        time.sleep(2)
    sys.exit("timed out waiting for the importer; check `adb logcat -s Whoogoo`")


def verify_(args):
    verify.main(args.records)


def main():
    p = argparse.ArgumentParser(prog="whoogoo", description=__doc__.split(":")[0])
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("doctor", help="check the Android SDK setup").set_defaults(fn=doctor)
    e = sub.add_parser("emu", help="create (first run) and boot the emulator")
    e.add_argument("--headless", action="store_true", help="no window (you cannot sign in to Google this way)")
    e.set_defaults(fn=emu)
    c = sub.add_parser("convert", help="convert a WHOOP export to Health Connect records JSON")
    c.add_argument("export", help="WHOOP export zip or unzipped directory")
    c.add_argument("-o", "--out", default="records.json")
    c.set_defaults(fn=convert_)
    i = sub.add_parser("import", help="convert, then load the records into Health Connect on the running emulator")
    i.add_argument("source", help="WHOOP export zip, directory, or a records.json")
    i.add_argument("--apk", help="local importer APK instead of the latest GitHub release")
    i.set_defaults(fn=import_)
    v = sub.add_parser("verify", help="diff your Google Health account against the imported records")
    v.add_argument("records", nargs="?", default=str(RECORDS))
    v.set_defaults(fn=verify_)
    args = p.parse_args()
    args.fn(args)
