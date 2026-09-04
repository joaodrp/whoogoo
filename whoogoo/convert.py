#!/usr/bin/env python3
"""Convert a WHOOP data export (zip or unzipped dir) into Health Connect records as JSON."""
import csv
import io
import json
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from statistics import mean

# WHOOP activity name -> ExerciseSessionRecord.EXERCISE_TYPE_* suffix. Anything else -> OTHER_WORKOUT,
# with the WHOOP name kept as the session title.
EXERCISE = {
    "Cycling": "BIKING", "Mountain Biking": "BIKING", "Spin": "BIKING_STATIONARY",
    "Spinning": "BIKING_STATIONARY", "Indoor Cycling": "BIKING_STATIONARY",
    "Running": "RUNNING", "Jogging": "RUNNING", "Track & Field": "RUNNING", "Treadmill": "RUNNING_TREADMILL",
    "Walking": "WALKING", "Stroller Walking": "WALKING", "Rucking": "WALKING", "Hiking": "HIKING",
    "Weightlifting": "WEIGHTLIFTING", "Powerlifting": "WEIGHTLIFTING", "Strength Trainer": "STRENGTH_TRAINING",
    "Functional Fitness": "HIGH_INTENSITY_INTERVAL_TRAINING", "HIIT": "HIGH_INTENSITY_INTERVAL_TRAINING",
    "CrossFit": "HIGH_INTENSITY_INTERVAL_TRAINING", "Boot Camp": "BOOT_CAMP", "Calisthenics": "CALISTHENICS",
    "Yoga": "YOGA", "Pilates": "PILATES", "Barre": "EXERCISE_CLASS", "Stretching": "STRETCHING",
    "Meditation": "GUIDED_BREATHING", "Breathwork": "GUIDED_BREATHING",
    "Rowing": "ROWING", "Indoor Rowing": "ROWING_MACHINE", "Elliptical": "ELLIPTICAL",
    "Stairmaster": "STAIR_CLIMBING_MACHINE", "Stair Climbing": "STAIR_CLIMBING",
    "Swimming": "SWIMMING_POOL", "Open Water Swimming": "SWIMMING_OPEN_WATER",
    "Paddleboarding": "PADDLING", "Kayaking": "PADDLING", "Canoeing": "PADDLING", "Surfing": "SURFING",
    "Sailing": "SAILING", "Scuba Diving": "SCUBA_DIVING", "Water Polo": "WATER_POLO",
    "Boxing": "BOXING", "Kickboxing": "MARTIAL_ARTS", "Martial Arts": "MARTIAL_ARTS",
    "Jiu Jitsu": "MARTIAL_ARTS", "Muay Thai": "MARTIAL_ARTS", "Fencing": "FENCING",
    "Climbing": "ROCK_CLIMBING", "Rock Climbing": "ROCK_CLIMBING", "Bouldering": "ROCK_CLIMBING",
    "Skiing": "SKIING", "Cross Country Skiing": "SKIING", "Snowboarding": "SNOWBOARDING",
    "Snowshoeing": "SNOWSHOEING", "Ice Skating": "ICE_SKATING", "Skateboarding": "SKATING",
    "Inline Skating": "SKATING", "Roller Skating": "SKATING", "Paragliding": "PARAGLIDING",
    "Tennis": "TENNIS", "Table Tennis": "TABLE_TENNIS", "Badminton": "BADMINTON", "Squash": "SQUASH",
    "Racquetball": "RACQUETBALL", "Golf": "GOLF", "Soccer": "SOCCER", "Basketball": "BASKETBALL",
    "Baseball": "BASEBALL", "Softball": "SOFTBALL", "Volleyball": "VOLLEYBALL", "Cricket": "CRICKET",
    "Handball": "HANDBALL", "Rugby": "RUGBY", "American Football": "FOOTBALL_AMERICAN",
    "Australian Football": "FOOTBALL_AUSTRALIAN", "Ice Hockey": "ICE_HOCKEY", "Roller Hockey": "ROLLER_HOCKEY",
    "Ultimate": "FRISBEE_DISC", "Disc Golf": "FRISBEE_DISC", "Gymnastics": "GYMNASTICS", "Dance": "DANCING",
    "Wheelchair": "WHEELCHAIR",
}
STAGES = {"LIGHT": "Light sleep duration (min)", "DEEP": "Deep (SWS) duration (min)",
          "REM": "REM duration (min)", "AWAKE": "Awake duration (min)"}


def ts(local, tz):
    """WHOOP times are local; tz is 'UTC+01:00' or 'UTCZ'."""
    off = "+00:00" if tz == "UTCZ" else tz[3:]
    return datetime.fromisoformat(f"{local.replace(' ', 'T')}{off}")


def read(src):
    src = Path(src)
    if src.suffix == ".zip":
        with zipfile.ZipFile(src) as z:
            return {n: list(csv.DictReader(io.TextIOWrapper(z.open(n), encoding="utf-8")))
                    for n in z.namelist() if n.endswith(".csv")}
    return {p.name: list(csv.DictReader(p.open(encoding="utf-8"))) for p in src.glob("*.csv")}


def sleeps(rows):
    for r in rows:
        tz = r["Cycle timezone"]
        start, end = ts(r["Sleep onset"], tz), ts(r["Wake onset"], tz)
        mins = {k: int(r[col]) for k, col in STAGES.items()}
        total = sum(mins.values())
        # ponytail: WHOOP exports stage totals only, so stages are contiguous blocks scaled to the
        # session; totals are exact, the hypnogram shape is not. Real intervals need the WHOOP API.
        stages, cum, t = [], 0, start
        for name, m in mins.items():
            if not m:
                continue
            cum += m
            seg_end = end if cum == total else start + (end - start) * cum / total
            stages.append({"start": t.isoformat(), "end": seg_end.isoformat(), "stage": name})
            t = seg_end
        yield {"type": "sleep", "id": f"whoop:sleep:{r['Sleep onset']}",
               "start": start.isoformat(), "end": end.isoformat(),
               "title": "Nap" if r["Nap"] == "true" else "Sleep", "stages": stages}
        if r["Respiratory rate (rpm)"]:
            yield {"type": "respiratory_rate", "id": f"whoop:rr:{r['Sleep onset']}",
                   "time": end.isoformat(), "rpm": float(r["Respiratory rate (rpm)"])}


def cycles(rows):
    # Vitals are measured during sleep; stamp them at wake time so they land on the right day.
    temps = [float(r["Skin temp (celsius)"]) for r in rows if r["Skin temp (celsius)"]]
    baseline = round(mean(temps), 2)
    for r in rows:
        tz, key = r["Cycle timezone"], r["Cycle start time"]
        if r["Energy burned (cal)"] and r["Cycle end time"]:
            yield {"type": "total_calories", "id": f"whoop:cal:{key}",
                   "start": ts(key, tz).isoformat(), "end": ts(r["Cycle end time"], tz).isoformat(),
                   "kcal": float(r["Energy burned (cal)"])}
        if not r["Wake onset"]:
            continue
        wake = ts(r["Wake onset"], tz).isoformat()
        if r["Resting heart rate (bpm)"]:
            yield {"type": "resting_heart_rate", "id": f"whoop:rhr:{key}", "time": wake,
                   "bpm": int(r["Resting heart rate (bpm)"])}
        if r["Heart rate variability (ms)"]:
            yield {"type": "hrv", "id": f"whoop:hrv:{key}", "time": wake,
                   "ms": float(r["Heart rate variability (ms)"])}
        if r["Blood oxygen %"]:
            yield {"type": "spo2", "id": f"whoop:spo2:{key}", "time": wake,
                   "pct": float(r["Blood oxygen %"])}
        if r["Skin temp (celsius)"]:
            yield {"type": "skin_temperature", "id": f"whoop:temp:{key}",
                   "start": ts(r["Sleep onset"], tz).isoformat(), "end": wake,
                   "baseline": baseline, "delta": round(float(r["Skin temp (celsius)"]) - baseline, 2)}


def workouts(rows):
    for r in rows:
        tz, key = r["Cycle timezone"], r["Workout start time"]
        start, end = ts(key, tz).isoformat(), ts(r["Workout end time"], tz).isoformat()
        yield {"type": "exercise", "id": f"whoop:workout:{key}", "start": start, "end": end,
               "exerciseType": EXERCISE.get(r["Activity name"], "OTHER_WORKOUT"),
               "title": r["Activity name"]}
        if float(r["Energy burned (cal)"]) > 0:
            yield {"type": "active_calories", "id": f"whoop:wcal:{key}", "start": start, "end": end,
                   "kcal": float(r["Energy burned (cal)"])}


def convert(src):
    data = read(src)
    return [*sleeps(data["sleeps.csv"]), *cycles(data["physiological_cycles.csv"]),
            *workouts(data["workouts.csv"])]


def check(records):
    ids = [r["id"] for r in records]
    assert len(ids) == len(set(ids)), "duplicate record ids"
    for r in records:
        if "start" in r:
            assert r["start"] < r["end"], r
        if r["type"] == "sleep" and r["stages"]:
            assert r["stages"][0]["start"] == r["start"] and r["stages"][-1]["end"] == r["end"], r
            assert all(a["end"] == b["start"] for a, b in zip(r["stages"], r["stages"][1:])), r


def write(src, out):
    """Convert src, validate, write JSON to out. Returns record counts per type."""
    records = convert(src)
    check(records)
    Path(out).parent.mkdir(parents=True, exist_ok=True)
    Path(out).write_text(json.dumps(records, indent=1))
    counts = {}
    for r in records:
        counts[r["type"]] = counts.get(r["type"], 0) + 1
    return counts
