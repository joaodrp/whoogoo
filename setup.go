package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
)

const (
	app = "dev.joaodrp.whoogoo"
	avd = "whoogoo"
)

var abi = map[string]string{"arm64": "arm64-v8a", "amd64": "x86_64"}[runtime.GOARCH]

// image is the sdkmanager package id; its install path is the same id with "/" for ";".
var image = "system-images;android-36;google_apis_playstore;" + abi

var tools = map[string]string{
	"adb":        "platform-tools/adb",
	"emulator":   "emulator/emulator",
	"sdkmanager": "cmdline-tools/latest/bin/sdkmanager",
	"avdmanager": "cmdline-tools/latest/bin/avdmanager",
}

func home() string {
	h, _ := os.UserHomeDir()
	return h
}

func isDir(path string) bool {
	st, err := os.Stat(path)
	return err == nil && st.IsDir()
}

// brewPrefix is where Homebrew installs the android-commandlinetools cask; "" without Homebrew.
var brewPrefix = sync.OnceValue(func() string {
	if p := os.Getenv("HOMEBREW_PREFIX"); p != "" {
		return p
	}
	out, err := exec.Command("brew", "--prefix").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
})

func sdkRoot() string {
	candidates := []string{os.Getenv("ANDROID_HOME"), os.Getenv("ANDROID_SDK_ROOT"),
		filepath.Join(home(), "Android", "Sdk"), filepath.Join(home(), "Library", "Android", "sdk")}
	if p := brewPrefix(); p != "" {
		candidates = append(candidates, filepath.Join(p, "share", "android-commandlinetools"))
	}
	for _, c := range candidates {
		if c != "" && isDir(c) {
			return c
		}
	}
	return ""
}

// sdkTool returns the tool's path under the SDK root, "" if not installed there. The emulator
// needs platform-tools next to it, so a copy elsewhere on PATH does not count.
func sdkTool(name string) string {
	root := sdkRoot()
	if root == "" {
		return ""
	}
	p := filepath.Join(root, tools[name])
	if _, err := os.Stat(p); err != nil {
		return ""
	}
	return p
}

// tool prefers the SDK root, then PATH; "" if absent.
func tool(name string) string {
	if p := sdkTool(name); p != "" {
		return p
	}
	p, _ := exec.LookPath(name)
	return p
}

// avdHome is where virtual devices live. Exported to every SDK tool because recent avdmanager
// builds default to an XDG path the emulator does not read.
func avdHome() string {
	if h := os.Getenv("ANDROID_AVD_HOME"); h != "" {
		return h
	}
	return filepath.Join(home(), ".android", "avd")
}

// command builds an exec.Cmd with the SDK root and AVD home exported, which the emulator
// requires to find system images, platform-tools and the device.
func command(cmd ...string) *exec.Cmd {
	c := exec.Command(cmd[0], cmd[1:]...)
	c.Env = append(os.Environ(), "ANDROID_AVD_HOME="+avdHome())
	if root := sdkRoot(); root != "" {
		c.Env = append(c.Env, "ANDROID_HOME="+root, "ANDROID_SDK_ROOT="+root)
	}
	return c
}

func which(name string) bool {
	_, err := exec.LookPath(name)
	return err == nil
}

// run executes cmd with the given stdin, streaming its output to the terminal.
func run(stdin string, cmd ...string) error {
	c := command(cmd...)
	c.Stdin, c.Stdout, c.Stderr = strings.NewReader(stdin), os.Stdout, os.Stderr
	return c.Run()
}

type requirement struct {
	label string
	ok    bool
	hint  string
	pkg   string   // sdkmanager package that provides it, batched into one install
	cmd   []string // or a command setup can offer to run; both empty when only a manual step helps
}

func checks() []requirement {
	root := sdkRoot()
	var sdkFix, javaFix []string
	if runtime.GOOS == "darwin" && which("brew") {
		sdkFix = []string{"brew", "install", "--cask", "android-commandlinetools"}
	}
	if which("mise") {
		javaFix = []string{"mise", "use", "-g", "java@21"}
	}
	imageDir := filepath.Join(root, filepath.Join(strings.Split(image, ";")...))
	out := []requirement{
		{label: "Android SDK", ok: root != "", hint: "install Android Studio or the command-line tools and set ANDROID_HOME", cmd: sdkFix},
		{label: "java (for sdkmanager/avdmanager)", ok: which("java"), hint: "install a JDK", cmd: javaFix},
		{label: "sdkmanager", ok: tool("sdkmanager") != "", hint: "reinstall the command-line tools"},
		{label: "avdmanager", ok: tool("avdmanager") != "", hint: "reinstall the command-line tools"},
		{label: "adb (platform-tools)", ok: sdkTool("adb") != "", hint: `sdkmanager "platform-tools"`, pkg: "platform-tools"},
		{label: "emulator", ok: sdkTool("emulator") != "", hint: `sdkmanager "emulator"`, pkg: "emulator"},
		{label: "Android 16 Play Store image", ok: root != "" && isDir(imageDir), hint: fmt.Sprintf("sdkmanager %q", image), pkg: image},
	}
	if runtime.GOOS == "linux" {
		f, err := os.OpenFile("/dev/kvm", os.O_RDWR, 0)
		if err == nil {
			_ = f.Close()
		}
		out = append(out, requirement{label: "/dev/kvm", ok: err == nil, hint: "add yourself to the kvm group"})
	}
	return out
}

// report prints the check list and returns what is missing.
func report() (missing []requirement) {
	for _, r := range checks() {
		if r.ok {
			fmt.Printf("ok       %s\n", r.label)
		} else {
			fmt.Printf("MISSING  %s  ->  %s\n", r.label, r.hint)
			missing = append(missing, r)
		}
	}
	return missing
}

func doctor() error {
	if len(report()) > 0 {
		return fmt.Errorf("run `whoogoo setup` to fix this interactively")
	}
	return nil
}

func isTerminal() bool {
	st, err := os.Stdin.Stat()
	return err == nil && st.Mode()&os.ModeCharDevice != 0
}

type fix struct {
	cmd   []string
	stdin string
}

// fixes turns missing requirements into commands: one sdkmanager call for all packages, other
// commands as they are. Nil when only manual steps remain.
func fixes(missing []requirement) (out []fix) {
	var pkgs []string
	for _, r := range missing {
		if r.pkg != "" {
			pkgs = append(pkgs, r.pkg)
		} else if r.cmd != nil {
			out = append(out, fix{cmd: r.cmd})
		}
	}
	if sm := tool("sdkmanager"); len(pkgs) > 0 && sm != "" {
		out = append(out, fix{cmd: append([]string{sm}, pkgs...), stdin: strings.Repeat("y\n", 100)}) // accept licenses
	}
	return out
}

func setup(yes bool) error {
	// Each round can unlock the next: brew -> sdkmanager -> packages. Stop when a round makes no progress.
	for prev := -1; ; {
		missing := report()
		if len(missing) == 0 {
			break
		}
		todo := fixes(missing)
		if len(todo) == 0 || len(missing) == prev {
			return fmt.Errorf("still missing requirements, see above")
		}
		prev = len(missing)
		ran := false
		for _, f := range todo {
			shown := strings.Join(f.cmd, " ")
			if !yes {
				if !isTerminal() {
					fmt.Printf("skipping (no terminal; pass -y to run it): %s\n", shown)
					continue
				}
				fmt.Printf("\nrun: %s ? [Y/n] ", shown)
				answer, _ := bufio.NewReader(os.Stdin).ReadString('\n')
				if a := strings.ToLower(strings.TrimSpace(answer)); a != "" && a != "y" && a != "yes" {
					continue
				}
			}
			fmt.Printf("+ %s\n", shown)
			if err := run(f.stdin, f.cmd...); err != nil {
				return fmt.Errorf("%s: %w", shown, err)
			}
			ran = true
		}
		fmt.Println()
		if !ran {
			return fmt.Errorf("still missing requirements, see above")
		}
	}
	if err := ensureAVD(); err != nil {
		return err
	}
	fmt.Println("ready: run `whoogoo emu`")
	return nil
}

func ensureAVD() error {
	if isDir(filepath.Join(avdHome(), avd+".avd")) {
		return nil
	}
	fmt.Printf("creating virtual device %s\n", avd)
	args := []string{"create", "avd", "--force", "-n", avd, "-k", image}
	c := command(append([]string{tool("avdmanager")}, append(args, "-d", "pixel_10")...)...)
	c.Stdin = strings.NewReader("no\n")
	if _, err := c.CombinedOutput(); err == nil {
		return nil
	}
	fmt.Println("pixel_10 profile unavailable in these SDK tools, using the default profile")
	return run("no\n", append([]string{tool("avdmanager")}, args...)...)
}

func emu(headless bool) error {
	if err := doctor(); err != nil {
		return err
	}
	if err := ensureAVD(); err != nil {
		return err
	}
	args := []string{tool("emulator"), "-avd", avd, "-no-snapshot-load"}
	if headless {
		args = append(args, "-no-window", "-gpu", "swiftshader_indirect")
	}
	fmt.Println("booting emulator; leave this running and use another terminal for `whoogoo import`")
	return run("", args...)
}
