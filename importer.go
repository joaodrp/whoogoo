package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
)

const (
	// The APK from the CLI's own release: app and CLI share the log protocol, so they ship together.
	apkURL  = "https://github.com/joaodrp/whoogoo/releases/download/v%s/whoogoo_%s.apk"
	timeout = 10 * time.Minute // emulator boot, and the import itself
	staging = "/data/local/tmp/whoogoo-export.zip"
	// Where the app finds the export in its files directory; passed to it in the "zip" extra.
	exportFile = "export.zip"
)

var (
	adbPath = sync.OnceValue(func() string { return tool("adb") })
	// Same prefix the app filters its requested permissions on (MainActivity.kt).
	healthPermission = regexp.MustCompile(`android\.permission\.health\.[A-Z_]+`)
)

func adb(args ...string) (string, error) {
	out, err := command(append([]string{adbPath()}, args...)...).CombinedOutput()
	if err != nil {
		return string(out), fmt.Errorf("adb %s: %w\n%s", strings.Join(args, " "), err, out)
	}
	return string(out), nil
}

// waitForDevice returns once the device reports boot complete; adb may flap while the emulator
// boots, so each probe is a fresh, short-lived command.
func waitForDevice() error {
	if _, err := adb("wait-for-device"); err != nil {
		return err
	}
	for deadline := time.Now().Add(timeout); time.Now().Before(deadline); time.Sleep(3 * time.Second) {
		if out, _ := adb("shell", "getprop", "sys.boot_completed"); strings.TrimSpace(out) == "1" {
			return nil
		}
	}
	return fmt.Errorf("device did not finish booting within %s", timeout)
}

func downloadAPK() (string, error) {
	if err := os.MkdirAll(cacheDir(), 0o755); err != nil {
		return "", err
	}
	if version == "dev" {
		return "", fmt.Errorf("development build: pass --apk with a local build (mise run apk)")
	}
	url := fmt.Sprintf(apkURL, version, version)
	path := filepath.Join(cacheDir(), "whoogoo.apk")
	fmt.Println("downloading", url)
	resp, err := http.Get(url)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return "", fmt.Errorf("%s: HTTP %d (pass --apk to use a local build)", url, resp.StatusCode)
	}
	f, err := os.Create(path)
	if err != nil {
		return "", err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		return "", err
	}
	return path, f.Close()
}

// recordTypes are the "type" values Convert.kt writes; --skip names them.
var recordTypes = []string{"sleep", "respiratory_rate", "resting_heart_rate", "hrv", "spo2",
	"skin_temperature", "total_calories", "exercise", "active_calories"}

// filters narrow what the app imports; empty fields mean everything.
type filters struct {
	skip        []string
	from, until string
}

// extras turns the filters into `am start` arguments the app reads, rejecting typos here rather
// than after the emulator has booted.
func (f filters) extras() ([]string, error) {
	var args []string
	for _, s := range f.skip {
		if !slices.Contains(recordTypes, s) {
			return nil, fmt.Errorf("--skip %s: unknown record type (have %s)", s, strings.Join(recordTypes, ", "))
		}
	}
	if len(f.skip) > 0 {
		args = append(args, "--es", "skip", strings.Join(f.skip, ","))
	}
	for _, d := range []struct{ name, value string }{{"from", f.from}, {"until", f.until}} {
		if d.value == "" {
			continue
		}
		if _, err := time.Parse(time.DateOnly, d.value); err != nil {
			return nil, fmt.Errorf("--%s %s: want a date like 2026-05-31", d.name, d.value)
		}
		args = append(args, "--es", d.name, d.value)
	}
	return args, nil
}

func importCmd(zip, apk string, opts filters) error {
	if adbPath() == "" {
		return fmt.Errorf("adb not found; run `whoogoo setup`")
	}
	extras, err := opts.extras()
	if err != nil {
		return err
	}
	if st, err := os.Stat(zip); err != nil {
		return err
	} else if !st.Mode().IsRegular() {
		return fmt.Errorf("%s: expected the export zip file", zip)
	}
	if err := os.MkdirAll(cacheDir(), 0o755); err != nil {
		return err
	}
	if apk == "" {
		var err error
		if apk, err = downloadAPK(); err != nil {
			return err
		}
	}
	fmt.Println("waiting for the emulator")
	if err := waitForDevice(); err != nil {
		return err
	}
	// -d: a local build (versionCode 1) may replace a release.
	if _, err := adb("install", "-r", "-d", apk); err != nil {
		return err
	}
	if _, err := adb("push", zip, staging); err != nil {
		return err
	}
	// Grant whatever health permissions the installed app declares, so the manifest stays the only list.
	dump, err := adb("shell", "dumpsys", "package", app)
	if err != nil {
		return err
	}
	script := []string{
		"run-as " + app + " mkdir -p files",
		"run-as " + app + " cp " + staging + " files/" + exportFile,
		"rm " + staging,
	}
	for _, p := range slices.Compact(slices.Sorted(slices.Values(healthPermission.FindAllString(dump, -1)))) {
		script = append(script, "pm grant "+app+" "+p)
	}
	if _, err := adb("shell", strings.Join(script, " && ")); err != nil {
		return err
	}
	if _, err := adb("logcat", "-c"); err != nil {
		return err
	}
	launch := append([]string{"shell", "am", "start", "-n", app + "/.MainActivity", "--es", "zip", exportFile}, extras...)
	if _, err := adb(launch...); err != nil {
		return err
	}
	if err := followLog(); err != nil {
		return err
	}
	// exec-out keeps the bytes as written, unlike shell, which is meant for a terminal.
	records, err := command(adbPath(), "exec-out", "run-as", app, "cat", "files/records.json").Output()
	if err != nil {
		return fmt.Errorf("pulling records.json: %w", err)
	}
	if err := os.WriteFile(recordsPath(), records, 0o644); err != nil {
		return err
	}
	fmt.Println("records saved to", recordsPath(), "(for `whoogoo verify`)")
	return nil
}

// followLog streams the app's log until it reports "done" or an "error:" line.
func followLog() error {
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	c := command(adbPath(), "logcat", "-v", "raw", "-s", "Whoogoo")
	go func() { <-ctx.Done(); _ = c.Process.Kill() }()
	out, err := c.StdoutPipe()
	if err != nil {
		return err
	}
	if err := c.Start(); err != nil {
		return err
	}
	sc := bufio.NewScanner(out)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || strings.HasPrefix(line, "---") { // logcat buffer separators
			continue
		}
		fmt.Println(line)
		if line == "done" {
			return nil
		}
		if strings.HasPrefix(line, "error:") {
			return fmt.Errorf("import failed")
		}
	}
	return fmt.Errorf("timed out waiting for the importer; check `adb logcat -s Whoogoo`")
}
