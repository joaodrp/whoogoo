import json
import tempfile
import unittest
from pathlib import Path

from whoogoo import convert, verify

SLEEPS = """Cycle start time,Cycle end time,Cycle timezone,Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %,Nap
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 00:10:39,2026-07-26 07:39:40,86,14.0,427,449,239,110,78,22,508,49,95,81,false
"""
CYCLES = """Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Resting heart rate (bpm),Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %
2026-07-26 23:52:10,,UTCZ,41,59,42,33.82,97.47,,,,,2026-07-26 23:52:10,2026-07-27 07:52:09,88,13.8,466,479,277,88,101,13,560,52,97,87
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,76,53,54,33.92,97.61,17.8,2725,183,75,2026-07-26 00:10:39,2026-07-26 07:39:40,86,14.0,427,449,239,110,78,22,508,49,95,81
"""
WORKOUTS = """Cycle start time,Cycle end time,Cycle timezone,Workout start time,Workout end time,Duration (min),Activity name,Activity Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),HR Zone 1 %,HR Zone 2 %,HR Zone 3 %,HR Zone 4 %,HR Zone 5 %,GPS enabled
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 17:47:03,2026-07-26 19:24:40,97,Cycling,17.1,1137.0,183,147,17,16,42,23,1,false
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 20:00:00,2026-07-26 20:30:00,30,Underwater Basket Weaving,1.0,0.0,100,90,0,0,0,0,0,false
"""


class ConvertTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        for name, body in [("sleeps.csv", SLEEPS), ("physiological_cycles.csv", CYCLES), ("workouts.csv", WORKOUTS)]:
            (self.dir / name).write_text(body)

    def test_convert(self):
        counts = convert.write(self.dir, self.dir / "records.json")
        self.assertEqual(counts, {"sleep": 1, "respiratory_rate": 1, "total_calories": 1,
                                  "resting_heart_rate": 2, "hrv": 2, "spo2": 2, "skin_temperature": 2,
                                  "exercise": 2, "active_calories": 1})
        by = {r["id"]: r for r in json.loads((self.dir / "records.json").read_text())}
        sleep = by["whoop:sleep:2026-07-26 00:10:39"]
        self.assertEqual(sleep["start"], "2026-07-26T00:10:39+01:00")
        self.assertEqual([s["stage"] for s in sleep["stages"]], ["LIGHT", "DEEP", "REM", "AWAKE"])
        self.assertEqual(sleep["stages"][-1]["end"], sleep["end"])
        self.assertEqual(by["whoop:workout:2026-07-26 20:00:00"]["exerciseType"], "OTHER_WORKOUT")
        self.assertEqual(by["whoop:rhr:2026-07-26 23:52:10"]["time"], "2026-07-27T07:52:09+00:00")
        temps = [by[f"whoop:temp:{k}"] for k in ("2026-07-26 23:52:10", "2026-07-26 00:10:39")]
        self.assertAlmostEqual(temps[0]["baseline"] + temps[0]["delta"], 33.82, places=2)


class VerifyTest(unittest.TestCase):
    def test_compare_sessions(self):
        recs = [{"type": "sleep", "start": "2026-07-26T23:52:10+01:00", "end": "2026-07-27T07:52:09+01:00",
                 "stages": [{"start": "2026-07-26T23:52:10+01:00", "end": "2026-07-27T07:52:09+01:00", "stage": "LIGHT"}]},
                {"type": "sleep", "start": "2026-07-25T23:00:00+01:00", "end": "2026-07-26T07:00:00+01:00", "stages": []}]
        google = [{"sleep": {"interval": {"startTime": "2026-07-26T22:52:40Z", "endTime": "2026-07-27T06:52:09Z"},
                             "stages": [{"startTime": "2026-07-26T22:52:10Z", "endTime": "2026-07-27T06:52:09Z", "type": "LIGHT"}]}}]
        matched, differ, missing = verify.compare(recs, google, verify.CHECKS["sleep"])
        self.assertEqual((matched, differ, len(missing)), (1, [], 1))

    def test_compare_daily(self):
        recs = [{"type": "resting_heart_rate", "time": "2026-07-27T07:52:09+01:00", "bpm": 53}]
        google = [{"dailyRestingHeartRate": {"date": {"year": 2026, "month": 7, "day": 27}, "beatsPerMinute": "55"}}]
        self.assertEqual(verify.compare(recs, google, verify.CHECKS["resting_heart_rate"])[1], [("2026-07-27", 53, 55)])


if __name__ == "__main__":
    unittest.main()
