// whoogoo imports a WHOOP data export into Google Health through Android Health Connect.
package main

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/spf13/cobra"
)

var version = "dev"

func main() {
	root := &cobra.Command{
		Use:     "whoogoo",
		Short:   "Import a WHOOP data export into Google Health",
		Long:    "whoogoo loads a WHOOP data export into Android Health Connect on an emulator, from where the Google Health app syncs it into your account.",
		Version: version,
		// Errors are reported once by main; usage only for usage errors.
		SilenceUsage:  true,
		SilenceErrors: true,
	}

	var yes bool
	setupC := &cobra.Command{
		Use:   "setup",
		Short: "Check the Android SDK, offer to install what is missing, create the device",
		Args:  cobra.NoArgs,
		RunE:  func(*cobra.Command, []string) error { return setup(yes) },
	}
	setupC.Flags().BoolVarP(&yes, "yes", "y", false, "run the fixes without asking")

	doctorC := &cobra.Command{
		Use:   "doctor",
		Short: "Read-only version of setup",
		Args:  cobra.NoArgs,
		RunE:  func(*cobra.Command, []string) error { return doctor() },
	}

	var headless bool
	var gpu string
	emuC := &cobra.Command{
		Use:   "emu",
		Short: "Boot the emulator (leave it running)",
		Args:  cobra.NoArgs,
		RunE:  func(*cobra.Command, []string) error { return emu(headless, gpu) },
	}
	emuC.Flags().BoolVar(&headless, "headless", false, "no window (you cannot sign in to Google this way)")
	emuC.Flags().StringVar(&gpu, "gpu", "", `emulator -gpu mode, e.g. "host" when the emulator wrongly falls back to software rendering`)

	var apk string
	var opts filters
	importC := &cobra.Command{
		Use:   "import <export.zip>",
		Short: "Load a WHOOP export into Health Connect on the running emulator",
		Long:  "Installs the whoogoo app on the emulator, hands it the export and streams its progress. The records it wrote are pulled back for `verify`.",
		Args:  cobra.ExactArgs(1),
		RunE:  func(_ *cobra.Command, args []string) error { return importCmd(args[0], apk, opts) },
	}
	importC.Flags().StringVar(&apk, "apk", "", "local app APK instead of the one from this version's GitHub release")
	importC.Flags().StringSliceVar(&opts.skip, "skip", nil,
		"record types to leave out: "+strings.Join(recordTypes, ", "))
	importC.Flags().StringVar(&opts.from, "from", "", "skip records before this date (YYYY-MM-DD)")
	importC.Flags().StringVar(&opts.until, "until", "", "skip records after this date (YYYY-MM-DD), to stop where another device took over")

	verifyC := &cobra.Command{
		Use:   "verify [records.json]",
		Short: "Diff your Google Health account against the imported records",
		Long:  "Reads your account through the Google Health API (read-only scopes) and reports, per type, how many imported records match, differ, or are missing. Needs GOOGLE_HEALTH_CLIENT_ID and GOOGLE_HEALTH_CLIENT_SECRET.",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			if len(args) == 1 {
				return verify(args[0])
			}
			return verify(recordsPath())
		},
	}

	root.AddCommand(setupC, doctorC, emuC, importC, verifyC)
	if err := root.Execute(); err != nil {
		root.PrintErrln("error:", err)
		os.Exit(1)
	}
}

func cacheDir() string {
	d, err := os.UserCacheDir()
	if err != nil {
		d = os.TempDir()
	}
	return filepath.Join(d, "whoogoo")
}

func recordsPath() string { return filepath.Join(cacheDir(), "records.json") }
