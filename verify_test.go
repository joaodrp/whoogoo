package main

import "testing"

func specNamed(t *testing.T, name string) spec {
	t.Helper()
	for _, s := range specs {
		if s.name == name {
			return s
		}
	}
	t.Fatalf("no spec named %q", name)
	return spec{}
}

func TestCompareSessions(t *testing.T) {
	records := []Record{
		{"type": "exercise", "start": "2026-07-26T17:47:03+01:00", "end": "2026-07-26T19:24:40+01:00"},
		{"type": "exercise", "start": "2026-07-25T20:00:00+01:00", "end": "2026-07-25T20:30:00+01:00"},
	}
	google := []point{{"exercise": map[string]any{
		"interval": map[string]any{"startTime": "2026-07-26T16:47:33Z", "endTime": "2026-07-26T18:24:40Z"},
	}}}
	matched, differ, missing := compare(records, google, specNamed(t, "exercise"))
	if matched != 1 || len(differ) != 0 || len(missing) != 1 {
		t.Errorf("matched=%d differ=%v missing=%v", matched, differ, missing)
	}
}

// A records.json written by a different version of the app can lack a field a spec reads.
func TestCompareToleratesMissingFields(t *testing.T) {
	records := []Record{{"type": "exercise"}}
	google := []point{{"exercise": map[string]any{
		"interval": map[string]any{"startTime": "2026-07-26T16:47:33Z", "endTime": "2026-07-26T18:24:40Z"},
	}}}
	matched, differ, missing := compare(records, google, specNamed(t, "exercise"))
	if matched != 0 || len(differ) != 0 || len(missing) != 1 {
		t.Errorf("matched=%d differ=%v missing=%v", matched, differ, missing)
	}
}

func TestCompareDaily(t *testing.T) {
	records := []Record{{"type": "resting_heart_rate", "time": "2026-07-27T07:52:09+01:00", "bpm": 53.0}}
	google := []point{{"dailyRestingHeartRate": map[string]any{
		"date": map[string]any{"year": 2026.0, "month": 7.0, "day": 27.0}, "beatsPerMinute": "55"}}}
	_, differ, missing := compare(records, google, specNamed(t, "resting_heart_rate"))
	if len(missing) != 0 || len(differ) != 1 || differ[0].key != "2026-07-27" {
		t.Errorf("differ=%v missing=%v", differ, missing)
	}
}
