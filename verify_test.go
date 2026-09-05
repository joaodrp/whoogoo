package main

import "testing"

func TestCompareSessions(t *testing.T) {
	records := []Record{
		{"type": "sleep", "start": "2026-07-26T23:52:10+01:00", "end": "2026-07-27T07:52:09+01:00"},
		{"type": "sleep", "start": "2026-07-25T23:00:00+01:00", "end": "2026-07-26T07:00:00+01:00"},
	}
	google := []point{{"sleep": map[string]any{
		"interval": map[string]any{"startTime": "2026-07-26T22:52:40Z", "endTime": "2026-07-27T06:52:09Z"},
	}}}
	matched, differ, missing := compare(records, google, specs[0])
	if matched != 1 || len(differ) != 0 || len(missing) != 1 {
		t.Errorf("matched=%d differ=%v missing=%v", matched, differ, missing)
	}
}

// A records.json from a different version of the app can lack a field a spec reads.
func TestCompareToleratesMissingFields(t *testing.T) {
	records := []Record{{"type": "sleep"}}
	google := []point{{"sleep": map[string]any{
		"interval": map[string]any{"startTime": "2026-07-26T22:52:40Z", "endTime": "2026-07-27T06:52:09Z"},
	}}}
	if matched, differ, missing := compare(records, google, specs[0]); matched != 0 || len(differ) != 0 || len(missing) != 1 {
		t.Errorf("matched=%d differ=%v missing=%v", matched, differ, missing)
	}
}

func TestCompareDaily(t *testing.T) {
	records := []Record{{"type": "resting_heart_rate", "time": "2026-07-27T07:52:09+01:00", "bpm": 53.0}}
	google := []point{{"dailyRestingHeartRate": map[string]any{
		"date": map[string]any{"year": 2026.0, "month": 7.0, "day": 27.0}, "beatsPerMinute": "55"}}}
	_, differ, missing := compare(records, google, specs[2])
	if len(missing) != 0 || len(differ) != 1 || differ[0].key != "2026-07-27" {
		t.Errorf("differ=%v missing=%v", differ, missing)
	}
}
