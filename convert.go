package main

import (
	"archive/zip"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"io/fs"
	"maps"
	"math"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"time"
)

// Record is one Health Connect record as the importer app reads it; see MainActivity.kt.
type Record map[string]any

type row map[string]string

// exerciseTypes maps WHOOP activity names to ExerciseSessionRecord.EXERCISE_TYPE_* suffixes.
// Anything else becomes OTHER_WORKOUT with the WHOOP name kept as the session title.
var exerciseTypes = map[string]string{
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

var stageColumns = []struct{ stage, column string }{
	{"LIGHT", "Light sleep duration (min)"}, {"DEEP", "Deep (SWS) duration (min)"},
	{"REM", "REM duration (min)"}, {"AWAKE", "Awake duration (min)"},
}

// parseTS reads a WHOOP local timestamp with its cycle timezone ("UTC+01:00" or "UTCZ").
func parseTS(local, tz string) (time.Time, error) {
	t, err := time.Parse("2006-01-02 15:04:05 UTCZ07:00", local+" "+tz)
	// Parse adopts the machine's local zone when the offset matches it; pin the offset so output
	// does not depend on where the tool runs (visible on DST-change nights).
	_, off := t.Zone()
	return t.In(time.FixedZone(tz, off)), err
}

func iso(t time.Time) string { return t.Format(time.RFC3339Nano) }

// numbers reads numeric cells of one row, keeping the first parse error so a bad cell aborts the
// conversion instead of becoming a zero-valued record.
type numbers struct {
	r   row
	err error
}

func (n *numbers) f(col string) float64 {
	v, err := strconv.ParseFloat(n.r[col], 64)
	if err != nil && n.err == nil {
		n.err = fmt.Errorf("%s: %w", col, err)
	}
	return v
}

func round2(f float64) float64 { return math.Round(f*100) / 100 }

func readCSV(r io.Reader) ([]row, error) {
	recs, err := csv.NewReader(r).ReadAll()
	if err != nil || len(recs) == 0 {
		return nil, err
	}
	var rows []row
	for _, rec := range recs[1:] {
		m := row{}
		for i, h := range recs[0] {
			if i < len(rec) {
				m[h] = rec[i]
			}
		}
		rows = append(rows, m)
	}
	return rows, nil
}

type part struct {
	file string
	fn   func([]row) ([]Record, error)
}

var parts = []part{{"sleeps.csv", sleeps}, {"physiological_cycles.csv", cycles}, {"workouts.csv", workouts}}

// readExport loads the CSVs this tool uses from a WHOOP export zip (at any depth inside it) or an
// unzipped directory (top level only).
func readExport(src string) (map[string][]row, error) {
	files := map[string][]row{}
	load := func(fsys fs.FS, path string) error {
		f, err := fsys.Open(path)
		if err != nil {
			return err
		}
		defer f.Close()
		files[filepath.Base(path)], err = readCSV(f)
		return err
	}
	st, err := os.Stat(src)
	if err != nil {
		return nil, err
	}
	if st.IsDir() {
		for _, p := range parts {
			if err := load(os.DirFS(src), p.file); err != nil && !os.IsNotExist(err) {
				return nil, err
			}
		}
		return files, nil
	}
	z, err := zip.OpenReader(src)
	if err != nil {
		return nil, err
	}
	defer z.Close()
	wanted := func(name string) bool {
		return slices.ContainsFunc(parts, func(p part) bool { return p.file == name })
	}
	err = fs.WalkDir(z, ".", func(path string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() || !wanted(d.Name()) {
			return err
		}
		return load(z, path)
	})
	return files, err
}

func sleeps(rows []row) ([]Record, error) {
	var out []Record
	for _, r := range rows {
		tz := r["Cycle timezone"]
		start, err := parseTS(r["Sleep onset"], tz)
		if err != nil {
			return nil, err
		}
		end, err := parseTS(r["Wake onset"], tz)
		if err != nil {
			return nil, err
		}
		n := &numbers{r: r}
		total := 0.0
		for _, sc := range stageColumns {
			total += n.f(sc.column)
		}
		// ponytail: WHOOP exports stage totals only, so stages are contiguous blocks scaled to the
		// session; totals are exact, the hypnogram shape is not. Real intervals need the WHOOP API.
		stages := []any{}
		cum, t := 0.0, start
		for _, sc := range stageColumns {
			m := n.f(sc.column)
			if m == 0 {
				continue
			}
			cum += m
			segEnd := end
			if cum != total {
				segEnd = start.Add(time.Duration(float64(end.Sub(start)) * cum / total))
			}
			stages = append(stages, map[string]any{"start": iso(t), "end": iso(segEnd), "stage": sc.stage})
			t = segEnd
		}
		title := "Sleep"
		if r["Nap"] == "true" {
			title = "Nap"
		}
		out = append(out, Record{"type": "sleep", "id": "whoop:sleep:" + r["Sleep onset"],
			"start": iso(start), "end": iso(end), "title": title, "stages": stages})
		if r["Respiratory rate (rpm)"] != "" {
			out = append(out, Record{"type": "respiratory_rate", "id": "whoop:rr:" + r["Sleep onset"],
				"time": iso(end), "rpm": n.f("Respiratory rate (rpm)")})
		}
		if n.err != nil {
			return nil, n.err
		}
	}
	return out, nil
}

func cycles(rows []row) ([]Record, error) {
	// Vitals are measured during sleep; stamp them at wake time so they land on the right day.
	sum, count := 0.0, 0
	for _, r := range rows {
		if r["Skin temp (celsius)"] != "" {
			n := &numbers{r: r}
			sum += n.f("Skin temp (celsius)")
			if n.err != nil {
				return nil, n.err
			}
			count++
		}
	}
	baseline := 0.0
	if count > 0 {
		baseline = round2(sum / float64(count))
	}
	var out []Record
	for _, r := range rows {
		tz, key, n := r["Cycle timezone"], r["Cycle start time"], &numbers{r: r}
		if r["Energy burned (cal)"] != "" && r["Cycle end time"] != "" {
			s, err := parseTS(key, tz)
			if err != nil {
				return nil, err
			}
			e, err := parseTS(r["Cycle end time"], tz)
			if err != nil {
				return nil, err
			}
			out = append(out, Record{"type": "total_calories", "id": "whoop:cal:" + key,
				"start": iso(s), "end": iso(e), "kcal": n.f("Energy burned (cal)")})
		}
		if r["Wake onset"] == "" || n.err != nil {
			if n.err != nil {
				return nil, n.err
			}
			continue
		}
		wakeT, err := parseTS(r["Wake onset"], tz)
		if err != nil {
			return nil, err
		}
		wake := iso(wakeT)
		if r["Resting heart rate (bpm)"] != "" {
			out = append(out, Record{"type": "resting_heart_rate", "id": "whoop:rhr:" + key, "time": wake,
				"bpm": int(n.f("Resting heart rate (bpm)"))})
		}
		if r["Heart rate variability (ms)"] != "" {
			out = append(out, Record{"type": "hrv", "id": "whoop:hrv:" + key, "time": wake, "ms": n.f("Heart rate variability (ms)")})
		}
		if r["Blood oxygen %"] != "" {
			out = append(out, Record{"type": "spo2", "id": "whoop:spo2:" + key, "time": wake, "pct": n.f("Blood oxygen %")})
		}
		if r["Skin temp (celsius)"] != "" {
			s, err := parseTS(r["Sleep onset"], tz)
			if err != nil {
				return nil, err
			}
			out = append(out, Record{"type": "skin_temperature", "id": "whoop:temp:" + key,
				"start": iso(s), "end": wake, "baseline": baseline, "delta": round2(n.f("Skin temp (celsius)") - baseline)})
		}
		if n.err != nil {
			return nil, n.err
		}
	}
	return out, nil
}

func workouts(rows []row) ([]Record, error) {
	var out []Record
	for _, r := range rows {
		tz, key, n := r["Cycle timezone"], r["Workout start time"], &numbers{r: r}
		s, err := parseTS(key, tz)
		if err != nil {
			return nil, err
		}
		e, err := parseTS(r["Workout end time"], tz)
		if err != nil {
			return nil, err
		}
		et, ok := exerciseTypes[r["Activity name"]]
		if !ok {
			et = "OTHER_WORKOUT"
		}
		out = append(out, Record{"type": "exercise", "id": "whoop:workout:" + key, "start": iso(s), "end": iso(e),
			"exerciseType": et, "title": r["Activity name"]})
		if kcal := n.f("Energy burned (cal)"); kcal > 0 {
			out = append(out, Record{"type": "active_calories", "id": "whoop:wcal:" + key,
				"start": iso(s), "end": iso(e), "kcal": kcal})
		}
		if n.err != nil {
			return nil, n.err
		}
	}
	return out, nil
}

func convert(src string) ([]Record, error) {
	files, err := readExport(src)
	if err != nil {
		return nil, err
	}
	var out []Record
	for _, part := range parts {
		rows, ok := files[part.file]
		if !ok {
			return nil, fmt.Errorf("%s not found in %s", part.file, src)
		}
		recs, err := part.fn(rows)
		if err != nil {
			return nil, fmt.Errorf("%s: %w", part.file, err)
		}
		out = append(out, recs...)
	}
	return out, check(out)
}

// check rejects data Health Connect would refuse: duplicate client IDs and empty or inverted intervals.
func check(records []Record) error {
	seen := map[string]bool{}
	for _, r := range records {
		id := r["id"].(string)
		if seen[id] {
			return fmt.Errorf("duplicate record id %s", id)
		}
		seen[id] = true
		if s, ok := r["start"].(string); ok && s >= r["end"].(string) {
			return fmt.Errorf("%s: start is not before end", id)
		}
	}
	return nil
}

// write converts src to out and returns the record counts per type.
func write(src, out string) (map[string]int, error) {
	records, err := convert(src)
	if err != nil {
		return nil, err
	}
	data, _ := json.MarshalIndent(records, "", " ")
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return nil, err
	}
	if err := os.WriteFile(out, data, 0o644); err != nil {
		return nil, err
	}
	counts := map[string]int{}
	for _, r := range records {
		counts[r["type"].(string)]++
	}
	return counts, nil
}

func countsString(counts map[string]int) string {
	total := 0
	var parts []string
	for _, k := range slices.Sorted(maps.Keys(counts)) {
		total += counts[k]
		parts = append(parts, fmt.Sprintf("%s=%d", k, counts[k]))
	}
	return fmt.Sprintf("%d records (%s)", total, strings.Join(parts, " "))
}

func convertCmd(src, out string) error {
	counts, err := write(src, out)
	if err != nil {
		return err
	}
	fmt.Printf("%s -> %s\n", countsString(counts), out)
	return nil
}
