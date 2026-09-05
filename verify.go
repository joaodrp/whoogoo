package main

import (
	"encoding/json"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"time"
)

// Record is one Health Connect record as the app wrote it to records.json (Convert.kt).
type Record map[string]any

// Google Health API, read-only: https://developers.google.com/health/reference/rest
const api = "https://health.googleapis.com/v4/users/me/dataTypes"

var scopes = strings.Join([]string{
	"https://www.googleapis.com/auth/googlehealth.sleep.readonly",
	"https://www.googleapis.com/auth/googlehealth.activity_and_fitness.readonly",
	"https://www.googleapis.com/auth/googlehealth.health_metrics_and_measurements.readonly",
}, " ")

type token struct {
	AccessToken  string  `json:"access_token"`
	RefreshToken string  `json:"refresh_token,omitempty"`
	ExpiresIn    float64 `json:"expires_in"`
	ExpiresAt    float64 `json:"expires_at"`
}

func tokenPath() string {
	d, err := os.UserConfigDir()
	if err != nil {
		d = home()
	}
	return filepath.Join(d, "whoogoo", "token.json")
}

// decodeJSON reads an API response into v, turning non-200 statuses into errors.
func decodeJSON(resp *http.Response, err error, v any) error {
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 {
		return fmt.Errorf("%s: HTTP %d\n%s", resp.Request.URL.Path, resp.StatusCode, body)
	}
	return json.Unmarshal(body, v)
}

func exchange(form url.Values) (t token, err error) {
	resp, err := http.PostForm("https://oauth2.googleapis.com/token", form)
	return t, decodeJSON(resp, err, &t)
}

func openBrowser(u string) {
	if cmd := map[string]string{"darwin": "open", "linux": "xdg-open"}[runtime.GOOS]; cmd != "" {
		_ = exec.Command(cmd, u).Start() // best effort; the URL is printed too
	}
}

// login runs the OAuth installed-app flow with a loopback redirect.
func login(clientID, secret string) (token, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return token{}, err
	}
	redirect := "http://" + ln.Addr().String()
	code := make(chan string, 1)
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "Signed in. You can close this tab.")
		code <- r.URL.Query().Get("code")
	})}
	go func() { _ = srv.Serve(ln) }() // ends when srv.Close runs
	defer srv.Close()
	q := url.Values{"client_id": {clientID}, "redirect_uri": {redirect}, "response_type": {"code"},
		"scope": {scopes}, "access_type": {"offline"}, "prompt": {"consent"}}
	authURL := "https://accounts.google.com/o/oauth2/v2/auth?" + q.Encode()
	fmt.Println("Opening browser for Google sign-in...\n" + authURL)
	openBrowser(authURL)
	c := <-code
	if c == "" {
		return token{}, fmt.Errorf("sign-in failed: no authorization code")
	}
	return exchange(url.Values{"code": {c}, "client_id": {clientID}, "client_secret": {secret},
		"redirect_uri": {redirect}, "grant_type": {"authorization_code"}})
}

func accessToken() (string, error) {
	clientID, secret := os.Getenv("GOOGLE_HEALTH_CLIENT_ID"), os.Getenv("GOOGLE_HEALTH_CLIENT_SECRET")
	if clientID == "" || secret == "" {
		return "", fmt.Errorf("set GOOGLE_HEALTH_CLIENT_ID and GOOGLE_HEALTH_CLIENT_SECRET")
	}
	var t token
	if data, err := os.ReadFile(tokenPath()); err == nil {
		if err := json.Unmarshal(data, &t); err != nil {
			return "", fmt.Errorf("%s: %w (delete it to sign in again)", tokenPath(), err)
		}
	} else if t, err = login(clientID, secret); err != nil {
		return "", err
	} else {
		fmt.Println("token cached at", tokenPath())
	}
	if t.ExpiresAt < float64(time.Now().Unix()+60) {
		if t.RefreshToken == "" {
			fresh, err := login(clientID, secret)
			if err != nil {
				return "", err
			}
			t = fresh
		} else {
			fresh, err := exchange(url.Values{"refresh_token": {t.RefreshToken}, "client_id": {clientID},
				"client_secret": {secret}, "grant_type": {"refresh_token"}})
			if err != nil {
				return "", err
			}
			t.AccessToken, t.ExpiresIn = fresh.AccessToken, fresh.ExpiresIn
		}
		t.ExpiresAt = float64(time.Now().Unix()) + t.ExpiresIn
		data, _ := json.Marshal(t)
		if err := os.MkdirAll(filepath.Dir(tokenPath()), 0o700); err != nil {
			return "", err
		}
		if err := os.WriteFile(tokenPath(), data, 0o600); err != nil {
			return "", err
		}
	}
	return t.AccessToken, nil
}

type point map[string]any

func listPoints(tok, dataType, filter string) ([]point, error) {
	var points []point
	page := ""
	for {
		q := url.Values{"pageSize": {"10000"}, "filter": {filter}}
		if page != "" {
			q.Set("pageToken", page)
		}
		req, _ := http.NewRequest("GET", fmt.Sprintf("%s/%s/dataPoints?%s", api, dataType, q.Encode()), nil)
		req.Header.Set("Authorization", "Bearer "+tok)
		var res struct {
			DataPoints    []point `json:"dataPoints"`
			NextPageToken string  `json:"nextPageToken"`
		}
		resp, err := http.DefaultClient.Do(req)
		if err := decodeJSON(resp, err, &res); err != nil {
			return nil, err
		}
		points = append(points, res.DataPoints...)
		if page = res.NextPageToken; page == "" {
			return points, nil
		}
	}
}

// --- keys and values used to match WHOOP records with Google data points ---

func parseISO(s string) time.Time {
	t, _ := time.Parse(time.RFC3339Nano, s)
	return t
}

func minuteKey(s string) string { return parseISO(s).UTC().Truncate(time.Minute).Format(time.RFC3339) }

func dateKey(s string) string { return parseISO(s).Format("2006-01-02") }

// get walks nested JSON objects; nil when any step is missing.
func get(p point, path ...string) any {
	var cur any = map[string]any(p)
	for _, k := range path {
		m, _ := cur.(map[string]any)
		cur = m[k]
	}
	return cur
}

func str(p point, path ...string) string {
	s, _ := get(p, path...).(string)
	return s
}

// gnum reads a JSON number, or an int64 field, which the API serializes as a string.
func gnum(p point, path ...string) any {
	switch v := get(p, path...).(type) {
	case float64:
		return v
	case string:
		if f, err := strconv.ParseFloat(v, 64); err == nil {
			return f
		}
	}
	return nil
}

func gdate(p point, field string) string {
	d, _ := get(p, field, "date").(map[string]any)
	return fmt.Sprintf("%04.0f-%02.0f-%02.0f", d["year"], d["month"], d["day"])
}

func minutes(start, end string) float64 { return parseISO(end).Sub(parseISO(start)).Minutes() }

func f(v any) float64 {
	if x, ok := v.(float64); ok {
		return x
	}
	return math.NaN()
}

type spec struct {
	name     string // records.json type
	dataType string // API data type; its filter field derives from it
	field    string
	daily    bool // keyed by date instead of start minute
	key      func(Record) string
	gkey     func(point) string
	value    func(Record) any
	gvalue   func(point) any
	tol      float64
}

// daily builds a spec for a per-day API type. recField is the record's timestamp, valueField the
// number inside the API object (e.g. dailyRestingHeartRate.beatsPerMinute).
func daily(name, dataType, recField, valueField string, value func(Record) any, tol float64) spec {
	snake := strings.ReplaceAll(dataType, "-", "_")
	camel := lowerCamel(snake)
	return spec{name: name, dataType: dataType, field: snake + ".date", daily: true,
		key:    func(r Record) string { return dateKey(r[recField].(string)) },
		gkey:   func(p point) string { return gdate(p, camel) },
		value:  value,
		gvalue: func(p point) any { return gnum(p, camel, valueField) },
		tol:    tol}
}

func lowerCamel(snake string) string {
	parts := strings.Split(snake, "_")
	for i := 1; i < len(parts); i++ {
		parts[i] = strings.ToUpper(parts[i][:1]) + parts[i][1:]
	}
	return strings.Join(parts, "")
}

var specs = []spec{
	{name: "sleep", dataType: "sleep", field: "sleep.interval.civil_end_time",
		key:   func(r Record) string { return minuteKey(r["start"].(string)) },
		gkey:  func(p point) string { return minuteKey(str(p, "sleep", "interval", "startTime")) },
		value: func(r Record) any { return minutes(r["start"].(string), r["end"].(string)) },
		gvalue: func(p point) any {
			return minutes(str(p, "sleep", "interval", "startTime"), str(p, "sleep", "interval", "endTime"))
		},
		tol: 2},
	{name: "exercise", dataType: "exercise", field: "exercise.interval.civil_start_time",
		key:   func(r Record) string { return minuteKey(r["start"].(string)) },
		gkey:  func(p point) string { return minuteKey(str(p, "exercise", "interval", "startTime")) },
		value: func(r Record) any { return minutes(r["start"].(string), r["end"].(string)) },
		gvalue: func(p point) any {
			return minutes(str(p, "exercise", "interval", "startTime"), str(p, "exercise", "interval", "endTime"))
		},
		tol: 2},
	daily("resting_heart_rate", "daily-resting-heart-rate", "time", "beatsPerMinute", func(r Record) any { return f(r["bpm"]) }, 1),
	daily("hrv", "daily-heart-rate-variability", "time", "averageHeartRateVariabilityMilliseconds", func(r Record) any { return f(r["ms"]) }, 1),
	daily("spo2", "daily-oxygen-saturation", "time", "averagePercentage", func(r Record) any { return f(r["pct"]) }, 0.5),
	daily("respiratory_rate", "daily-respiratory-rate", "time", "breathsPerMinute", func(r Record) any { return f(r["rpm"]) }, 0.5),
	daily("skin_temperature", "daily-sleep-temperature-derivations", "end", "nightlyTemperatureCelsius",
		func(r Record) any { return f(r["baseline"]) + f(r["delta"]) }, 0.3),
}

func close_(a, b any, tol float64) bool {
	af, bf := f(a), f(b)
	return !math.IsNaN(af) && !math.IsNaN(bf) && math.Abs(af-bf) <= tol
}

type mismatch struct {
	key    string
	whoop  any
	google any
}

// compare matches WHOOP records to Google points by key (sessions tolerate one minute of drift).
func compare(records []Record, google []point, s spec) (matched int, differ []mismatch, missing []string) {
	index := map[string]point{}
	for _, p := range google {
		index[s.gkey(p)] = p
	}
	if !s.daily { // neighbouring minutes only where no point starts exactly there
		for _, p := range google {
			t := parseISO(s.gkey(p))
			for _, d := range []time.Duration{time.Minute, -time.Minute} {
				if k := t.Add(d).Format(time.RFC3339); index[k] == nil {
					index[k] = p
				}
			}
		}
	}
	for _, r := range records {
		k := s.key(r)
		p, ok := index[k]
		switch {
		case !ok:
			missing = append(missing, k)
		case close_(s.value(r), s.gvalue(p), s.tol):
			matched++
		default:
			differ = append(differ, mismatch{k, s.value(r), s.gvalue(p)})
		}
	}
	return
}

func verify(path string) error {
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var records []Record
	if err := json.Unmarshal(data, &records); err != nil {
		return err
	}
	if len(records) == 0 {
		return fmt.Errorf("no records in %s", path)
	}
	byType := map[string][]Record{}
	var times []string
	for _, r := range records {
		byType[r["type"].(string)] = append(byType[r["type"].(string)], r)
		if t, ok := r["time"].(string); ok {
			times = append(times, t)
		} else {
			times = append(times, r["start"].(string))
		}
	}
	lo := parseISO(slices.Min(times)).AddDate(0, 0, -1).Format("2006-01-02")
	hi := parseISO(slices.Max(times)).AddDate(0, 0, 2).Format("2006-01-02")
	tok, err := accessToken()
	if err != nil {
		return err
	}
	failed := false
	fmt.Printf("%-20s %6s %6s %6s %6s %7s\n", "type", "whoop", "google", "match", "differ", "missing")
	for _, s := range specs {
		recs := byType[s.name]
		filter := fmt.Sprintf(`%s >= "%s" AND %s < "%s"`, s.field, lo, s.field, hi)
		google, err := listPoints(tok, s.dataType, filter)
		if err != nil {
			return err
		}
		matched, differ, missing := compare(recs, google, s)
		fmt.Printf("%-20s %6d %6d %6d %6d %7d\n", s.name, len(recs), len(google), matched, len(differ), len(missing))
		for _, d := range differ[:min(3, len(differ))] {
			fmt.Printf("    differ  %s: whoop=%v google=%v\n", d.key, d.whoop, d.google)
		}
		for _, k := range missing[:min(3, len(missing))] {
			fmt.Printf("    missing %s\n", k)
		}
		failed = failed || len(missing) > 0
	}
	if failed {
		return fmt.Errorf("some records are missing from Google Health")
	}
	return nil
}
