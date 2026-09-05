#!/usr/bin/env python3
"""Builds a fake WHOOP export, shaped like the real one but invented.

The screenshots in the README are taken against this, so no real health data
appears in the repository. The window ends just before the day it is run, so a
regenerated sample always looks like an export someone took recently:

    python3 docs/sample-export.py my_whoop_data_sample.zip
"""
import random
import sys
import zipfile
from datetime import datetime, timedelta

SLEEPS = ("Cycle start time,Cycle end time,Cycle timezone,Sleep onset,Wake onset,Sleep performance %,"
          "Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),"
          "Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),"
          "Sleep debt (min),Sleep efficiency %,Sleep consistency %,Nap")
CYCLES = ("Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Resting heart rate (bpm),"
          "Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,Energy burned (cal),"
          "Max HR (bpm),Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),"
          "Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),"
          "REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,"
          "Sleep consistency %")
WORKOUTS = ("Cycle start time,Cycle end time,Cycle timezone,Workout start time,Workout end time,"
            "Duration (min),Activity name,Activity Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),"
            "HR Zone 1 %,HR Zone 2 %,HR Zone 3 %,HR Zone 4 %,HR Zone 5 %,GPS enabled")
ACTIVITIES = ["Running", "Cycling", "Weightlifting", "Yoga", "Swimming", "Hiking", "Rowing", "Functional Fitness"]
TZ = "UTC+01:00"


def build(path, days=181, seed=7, end=None):
    random.seed(seed)
    fmt = lambda d: d.strftime("%Y-%m-%d %H:%M:%S")
    sleeps, cycles, workouts = [SLEEPS], [CYCLES], [WORKOUTS]
    end = end or datetime.now().replace(hour=23, minute=40, second=0, microsecond=0) - timedelta(days=4)
    start = end - timedelta(days=days - 1)
    for i in range(days):
        onset = start + timedelta(days=i)
        wake = onset + timedelta(hours=random.uniform(6.5, 8.5))
        cs, ce = onset, onset + timedelta(days=1)
        rr = round(random.uniform(13.2, 16.4), 1)
        asleep, bed = random.randint(380, 470), random.randint(400, 500)
        light, deep, rem, awake = (random.randint(180, 260), random.randint(70, 130),
                                   random.randint(60, 120), random.randint(10, 40))
        need, debt = random.randint(460, 560), random.randint(0, 90)
        perf, eff, cons = random.randint(60, 99), random.randint(88, 98), random.randint(60, 95)
        sleeps.append(f"{fmt(cs)},{fmt(ce)},{TZ},{fmt(onset)},{fmt(wake)},{perf},{rr},{asleep},{bed},"
                      f"{light},{deep},{rem},{awake},{need},{debt},{eff},{cons},false")
        cycles.append(f"{fmt(cs)},{fmt(ce)},{TZ},{random.randint(30, 99)},{random.randint(48, 62)},"
                      f"{random.randint(38, 92)},{round(random.uniform(33.4, 34.3), 2)},"
                      f"{round(random.uniform(94.5, 98.6), 2)},{round(random.uniform(6, 19), 1)},"
                      f"{random.randint(1900, 3200)},{random.randint(150, 190)},{random.randint(60, 90)},"
                      f"{fmt(onset)},{fmt(wake)},{perf},{rr},{asleep},{bed},{light},{deep},{rem},{awake},"
                      f"{need},{debt},{eff},{cons}")
        if random.random() < 0.55:
            ws = onset.replace(hour=random.randint(7, 19), minute=random.choice([0, 15, 30, 45]))
            duration = random.randint(25, 105)
            workouts.append(f"{fmt(cs)},{fmt(ce)},{TZ},{fmt(ws)},{fmt(ws + timedelta(minutes=duration))},"
                            f"{duration},{random.choice(ACTIVITIES)},{round(random.uniform(4, 18), 1)},"
                            f"{random.randint(180, 1200)}.0,{random.randint(150, 190)},"
                            f"{random.randint(110, 155)},{random.randint(5, 30)},{random.randint(5, 30)},"
                            f"{random.randint(10, 45)},{random.randint(5, 30)},{random.randint(0, 10)},false")
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("my_whoop_data/sleeps.csv", "\n".join(sleeps) + "\n")
        z.writestr("my_whoop_data/physiological_cycles.csv", "\n".join(cycles) + "\n")
        z.writestr("my_whoop_data/workouts.csv", "\n".join(workouts) + "\n")
    print(f"{path}: {len(sleeps) - 1} nights, {len(workouts) - 1} workouts")


if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "my_whoop_data_sample.zip")
