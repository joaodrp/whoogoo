#!/usr/bin/env python3
"""Check that records.json reached the Google Health account, via the Google Health API (read-only).

Needs GOOGLE_HEALTH_CLIENT_ID and GOOGLE_HEALTH_CLIENT_SECRET (an OAuth "Desktop app" client from a
Google Cloud project with the Google Health API enabled). The first run opens a browser for consent
and caches the token under ~/.config/whoogoo.
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request
import webbrowser
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

API = "https://health.googleapis.com/v4/users/me/dataTypes"
SCOPES = " ".join(f"https://www.googleapis.com/auth/googlehealth.{s}.readonly"
                  for s in ["sleep", "activity_and_fitness", "health_metrics_and_measurements"])
TOKEN_FILE = Path.home() / ".config" / "whoogoo" / "token.json"


# --- OAuth installed-app flow with loopback redirect, stdlib only ---

def post(url, data):
    req = urllib.request.Request(url, urllib.parse.urlencode(data).encode())
    with urllib.request.urlopen(req) as r:
        return json.load(r)


def login(client_id, client_secret):
    code = {}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            code.update(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query))
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"Signed in. You can close this tab.")

        def log_message(self, *_):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://127.0.0.1:{server.server_port}"
    url = "https://accounts.google.com/o/oauth2/v2/auth?" + urllib.parse.urlencode({
        "client_id": client_id, "redirect_uri": redirect, "response_type": "code",
        "scope": SCOPES, "access_type": "offline", "prompt": "consent"})
    print("Opening browser for Google sign-in...\n" + url)
    webbrowser.open(url)
    server.handle_request()
    if "code" not in code:
        sys.exit(f"sign-in failed: {code}")
    return post("https://oauth2.googleapis.com/token", {
        "code": code["code"][0], "client_id": client_id, "client_secret": client_secret,
        "redirect_uri": redirect, "grant_type": "authorization_code"})


def access_token():
    cid, secret = os.environ.get("GOOGLE_HEALTH_CLIENT_ID"), os.environ.get("GOOGLE_HEALTH_CLIENT_SECRET")
    if not (cid and secret):
        sys.exit("set GOOGLE_HEALTH_CLIENT_ID and GOOGLE_HEALTH_CLIENT_SECRET")
    tok = json.loads(TOKEN_FILE.read_text()) if TOKEN_FILE.exists() else login(cid, secret)
    if tok.get("expires_at", 0) < time.time() + 60:
        if "refresh_token" in tok:
            tok = {**tok, **post("https://oauth2.googleapis.com/token", {
                "refresh_token": tok["refresh_token"], "client_id": cid, "client_secret": secret,
                "grant_type": "refresh_token"})}
        tok["expires_at"] = time.time() + tok["expires_in"]
        TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
        TOKEN_FILE.write_text(json.dumps(tok))
    return tok["access_token"]


# --- Google Health API ---

def list_points(token, data_type, filter_):
    points, page = [], None
    while True:
        q = {"pageSize": 10000, "filter": filter_, **({"pageToken": page} if page else {})}
        req = urllib.request.Request(f"{API}/{data_type}/dataPoints?{urllib.parse.urlencode(q)}",
                                     headers={"Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req) as r:
                body = json.load(r)
        except urllib.error.HTTPError as e:
            sys.exit(f"{data_type}: HTTP {e.code}\n{e.read().decode()}")
        points += body.get("dataPoints", [])
        page = body.get("nextPageToken")
        if not page:
            return points


def utc(s):
    return datetime.fromisoformat(s.replace("Z", "+00:00")).astimezone(timezone.utc)


def minute_key(s):
    return utc(s).replace(second=0, microsecond=0)


def date_key(s):
    return datetime.fromisoformat(s).date().isoformat()


def gdate(d):
    return f"{d['year']:04d}-{d['month']:02d}-{d['day']:02d}"


def stage_minutes(stages, key):
    out = defaultdict(float)
    for st in stages:
        out[st[key]] += (utc(st["end"] if "end" in st else st["endTime"])
                         - utc(st["start"] if "start" in st else st["startTime"])).total_seconds() / 60
    return out


# --- Comparison: each entry = (records.json type, API data type, filter field, key fn, google key fn, value fn, google value fn, tolerance)

def sessions_filter(field, lo, hi):
    return f'{field} >= "{lo}" AND {field} < "{hi}"'


CHECKS = {
    "sleep": dict(
        data_type="sleep", field="sleep.interval.civil_end_time",
        key=lambda r: minute_key(r["start"]), gkey=lambda g: minute_key(g["sleep"]["interval"]["startTime"]),
        value=lambda r: stage_minutes(r["stages"], "stage"),
        gvalue=lambda g: stage_minutes(g["sleep"].get("stages", []), "type"), tol=3),
    "exercise": dict(
        data_type="exercise", field="exercise.interval.civil_start_time",
        key=lambda r: minute_key(r["start"]), gkey=lambda g: minute_key(g["exercise"]["interval"]["startTime"]),
        value=lambda r: (utc(r["end"]) - utc(r["start"])).total_seconds() / 60,
        gvalue=lambda g: (utc(g["exercise"]["interval"]["endTime"]) - utc(g["exercise"]["interval"]["startTime"])).total_seconds() / 60,
        tol=2),
    "resting_heart_rate": dict(
        data_type="daily-resting-heart-rate", field="daily_resting_heart_rate.date",
        key=lambda r: date_key(r["time"]), gkey=lambda g: gdate(g["dailyRestingHeartRate"]["date"]),
        value=lambda r: r["bpm"], gvalue=lambda g: int(g["dailyRestingHeartRate"]["beatsPerMinute"]), tol=1),
    "hrv": dict(
        data_type="daily-heart-rate-variability", field="daily_heart_rate_variability.date",
        key=lambda r: date_key(r["time"]), gkey=lambda g: gdate(g["dailyHeartRateVariability"]["date"]),
        value=lambda r: r["ms"], gvalue=lambda g: g["dailyHeartRateVariability"].get("averageHeartRateVariabilityMilliseconds"), tol=1),
    "spo2": dict(
        data_type="daily-oxygen-saturation", field="daily_oxygen_saturation.date",
        key=lambda r: date_key(r["time"]), gkey=lambda g: gdate(g["dailyOxygenSaturation"]["date"]),
        value=lambda r: r["pct"], gvalue=lambda g: g["dailyOxygenSaturation"].get("averagePercentage"), tol=0.5),
    "respiratory_rate": dict(
        data_type="daily-respiratory-rate", field="daily_respiratory_rate.date",
        key=lambda r: date_key(r["time"]), gkey=lambda g: gdate(g["dailyRespiratoryRate"]["date"]),
        value=lambda r: r["rpm"], gvalue=lambda g: g["dailyRespiratoryRate"].get("breathsPerMinute"), tol=0.5),
    "skin_temperature": dict(
        data_type="daily-sleep-temperature-derivations", field="daily_sleep_temperature_derivations.date",
        key=lambda r: date_key(r["end"]), gkey=lambda g: gdate(g["dailySleepTemperatureDerivations"]["date"]),
        value=lambda r: r["baseline"] + r["delta"],
        gvalue=lambda g: g["dailySleepTemperatureDerivations"].get("nightlyTemperatureCelsius"), tol=0.3),
}


def close(a, b, tol):
    if isinstance(a, dict):
        return all(abs(a.get(k, 0) - b.get(k, 0)) <= tol for k in set(a) | set(b))
    return a is not None and b is not None and abs(a - b) <= tol


def compare(records, google, c):
    """Returns (matched, value_mismatch, missing) for one check."""
    got = {c["gkey"](g): g for g in google}
    matched, mismatch, missing = 0, [], []
    for r in records:
        k = c["key"](r)
        g = got.get(k) or (got.get(k + timedelta(minutes=1)) or got.get(k - timedelta(minutes=1))
                           if isinstance(k, datetime) else None)
        if g is None:
            missing.append(k)
        elif close(c["value"](r), c["gvalue"](g), c["tol"]):
            matched += 1
        else:
            mismatch.append((k, c["value"](r), c["gvalue"](g)))
    return matched, mismatch, missing


def main(path):
    records = json.load(open(path))
    by_type = defaultdict(list)
    for r in records:
        by_type[r["type"]].append(r)
    times = [r.get("time") or r["start"] for r in records]
    lo = (datetime.fromisoformat(min(times)) - timedelta(days=1)).date().isoformat()
    hi = (datetime.fromisoformat(max(times)) + timedelta(days=2)).date().isoformat()
    token = access_token()
    failed = False
    print(f"{'type':20} {'whoop':>6} {'google':>6} {'match':>6} {'differ':>6} {'missing':>7}")
    for name, c in CHECKS.items():
        recs = by_type.get(name, [])
        google = list_points(token, c["data_type"], sessions_filter(c["field"], lo, hi))
        matched, mismatch, missing = compare(recs, google, c)
        print(f"{name:20} {len(recs):6} {len(google):6} {matched:6} {len(mismatch):6} {len(missing):7}")
        for k, a, b in mismatch[:3]:
            print(f"    differ  {k}: whoop={a} google={b}")
        for k in missing[:3]:
            print(f"    missing {k}")
        failed |= bool(missing)
    sys.exit(1 if failed else 0)

