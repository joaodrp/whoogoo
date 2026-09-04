package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

const sleepsCSV = `Cycle start time,Cycle end time,Cycle timezone,Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %,Nap
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 00:10:39,2026-07-26 07:39:40,86,14.0,427,449,239,110,78,22,508,49,95,81,false
`

const cyclesCSV = `Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Resting heart rate (bpm),Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %
2026-07-26 23:52:10,,UTCZ,41,59,42,33.82,97.47,,,,,2026-07-26 23:52:10,2026-07-27 07:52:09,88,13.8,466,479,277,88,101,13,560,52,97,87
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,76,53,54,33.92,97.61,17.8,2725,183,75,2026-07-26 00:10:39,2026-07-26 07:39:40,86,14.0,427,449,239,110,78,22,508,49,95,81
`

const workoutsCSV = `Cycle start time,Cycle end time,Cycle timezone,Workout start time,Workout end time,Duration (min),Activity name,Activity Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),HR Zone 1 %,HR Zone 2 %,HR Zone 3 %,HR Zone 4 %,HR Zone 5 %,GPS enabled
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 17:47:03,2026-07-26 19:24:40,97,Cycling,17.1,1137.0,183,147,17,16,42,23,1,false
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 20:00:00,2026-07-26 20:30:00,30,Underwater Basket Weaving,1.0,0.0,100,90,0,0,0,0,0,false
`

func TestConvert(t *testing.T) {
	dir := t.TempDir()
	for name, body := range map[string]string{"sleeps.csv": sleepsCSV, "physiological_cycles.csv": cyclesCSV, "workouts.csv": workoutsCSV} {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(body), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	out := filepath.Join(dir, "records.json")
	counts, err := write(dir, out)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]int{"sleep": 1, "respiratory_rate": 1, "total_calories": 1, "resting_heart_rate": 2,
		"hrv": 2, "spo2": 2, "skin_temperature": 2, "exercise": 2, "active_calories": 1}
	for k, v := range want {
		if counts[k] != v {
			t.Errorf("%s: got %d want %d", k, counts[k], v)
		}
	}
	data, _ := os.ReadFile(out)
	var records []Record
	if err := json.Unmarshal(data, &records); err != nil {
		t.Fatal(err)
	}
	by := map[string]Record{}
	for _, r := range records {
		by[r["id"].(string)] = r
	}
	sleep := by["whoop:sleep:2026-07-26 00:10:39"]
	if sleep["start"] != "2026-07-26T00:10:39+01:00" {
		t.Errorf("sleep start %v", sleep["start"])
	}
	stages := sleep["stages"].([]any)
	if len(stages) != 4 || stages[3].(map[string]any)["stage"] != "AWAKE" || stages[3].(map[string]any)["end"] != sleep["end"] {
		t.Errorf("stages %v", stages)
	}
	if by["whoop:workout:2026-07-26 20:00:00"]["exerciseType"] != "OTHER_WORKOUT" {
		t.Error("unknown activity should map to OTHER_WORKOUT")
	}
	if by["whoop:rhr:2026-07-26 23:52:10"]["time"] != "2026-07-27T07:52:09Z" {
		t.Errorf("rhr time %v", by["whoop:rhr:2026-07-26 23:52:10"]["time"])
	}
	temp := by["whoop:temp:2026-07-26 23:52:10"]
	if got := temp["baseline"].(float64) + temp["delta"].(float64); got < 33.81 || got > 33.83 {
		t.Errorf("skin temp reconstructs to %v", got)
	}
}
