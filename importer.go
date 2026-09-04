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

func importCmd(source, apk string) error {
	if adbPath() == "" {
		return fmt.Errorf("adb not found; run `whoogoo setup`")
	}
	records := source
	if !strings.HasSuffix(source, ".json") {
		records = recordsPath()
		counts, err := write(source, records)
		if err != nil {
			return err
		}
		fmt.Println(countsString(counts))
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
	if _, err := adb("install", "-r", apk); err != nil {
		return err
	}
	if _, err := adb("push", records, "/data/local/tmp/records.json"); err != nil {
		return err
	}
	// Grant whatever health permissions the installed app declares, so the manifest stays the only list.
	dump, err := adb("shell", "dumpsys", "package", app)
	if err != nil {
		return err
	}
	script := []string{
		"run-as " + app + " mkdir -p files",
		"run-as " + app + " cp /data/local/tmp/records.json files/records.json",
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
	if _, err := adb("shell", "am", "start", "-n", app+"/.MainActivity"); err != nil {
		return err
	}
	return followLog()
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
