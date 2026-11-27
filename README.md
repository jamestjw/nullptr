# Use your Android phone as a trackpad for Linux or macOS

1. Connect your phone and computer to the same Tailscale network.
2. Install the Android APK from [GitHub Releases](https://github.com/jamestjw/nullptr/releases/latest). If you built the debug APK instead, install it over USB with `adb install -r android/build/outputs/apk/debug/android-debug.apk`.
3. Download and extract the companion archive for your computer. On Linux, run `./nullptr-companion` in an X11 desktop session. On macOS, run it from Terminal in your logged-in desktop session. If you built the companion yourself, run `./companion/build/nullptr-companion`.
4. Open **nullptr trackpad** and tap **Scan pairing QR**. You can also enter the address and pairing code shown in the terminal.

Keep the companion running while you use the trackpad. Tap to click. Hold and slide to drag. Slide two fingers to scroll, or tap with two fingers to right-click. Open **Settings** to adjust pointer sensitivity or reverse scrolling.

Tap **Keyboard** to type into the focused desktop window. If your phone keyboard shows a word in its suggestion bar, press Space or pick the suggestion to send it. Swipe the row above the keyboard for Backspace, Enter, Tab, Escape, and the arrow keys.

On X11, the companion sends characters available in the active layout with an unmodified key or Shift. On macOS it sends Unicode text to the focused app; apps that only accept physical key codes may not handle that text. If **Keyboard** is disabled while the trackpad is connected, update the companion.

The companion listens on TCP port `47822` at its Tailscale IPv4 address. Tailscale encrypts the connection. If the phone cannot connect, check that your tailnet policy and desktop firewall allow the phone to reach that port. To show a new pairing code for a companion you start by hand, restart it with `--pair`.

### macOS setup

- Use macOS 13 or later. Choose `macos-arm64` for Apple Silicon or `macos-x86_64` for Intel.
- Release binaries are not notarized. If Gatekeeper blocks the downloaded companion, macOS shows **Open Anyway** under **System Settings → Privacy & Security** after the first launch attempt. You can also build the companion from source.
- Start Tailscale before running the companion. The companion uses `tailscale` on your `PATH` or the CLI inside `/Applications/Tailscale.app`.
- The first run requests permission to post input. Open **System Settings → Privacy & Security → Accessibility**, enable the terminal app or `nullptr-companion` shown in the list, and run the companion again.
- The pairing credential is saved in `~/Library/Application Support/nullptr/token`. Run the companion from Terminal each time you want to use it.

## Build both apps

Use JDK 17 or 21. Set `ANDROID_HOME` to an Android SDK with platform 35 and build tools 35.0.0.

```sh
./gradlew :android:assembleDebug
```

Install Go 1.25 or later. On Fedora, install GCC, pkg-config, `libX11-devel`, `libXtst-devel`, and `libxkbcommon-devel`. On macOS, install the Xcode Command Line Tools (`xcode-select --install`). From the project root, run:

```sh
mkdir -p companion/build
(cd companion && CGO_ENABLED=1 go build -o build/nullptr-companion .)
```

## Start the companion at login on Fedora

On Fedora x86-64, install Tailscale, `curl`, and `jq`. Run the installer from your X11 desktop session without `sudo`:

```sh
./install-companion.sh
```

The installer downloads the latest stable release, checks its checksum, and puts the binary in `~/.local/bin`. It installs a systemd user service and an XDG autostart entry. On Fedora i3, `dex-autostart` starts the service when you log in. If your phone is already paired and the port is free, the installer starts the service right away.

If a companion is already running in a terminal, stop it with Ctrl+C and run `systemctl --user start nullptr-companion.service`. Rerun the installer to update. It preserves your pairing and firewall settings.

To revoke the current pairing, run `./reset-pairing.sh`. It stops the service and deletes the desktop credential. Follow the printed commands to show a new QR code and restart the service after pairing. You can keep the Android app's data.

Check the service with `systemctl --user status nullptr-companion.service`. Read errors with `journalctl --user -u nullptr-companion.service`.

Run `./uninstall-companion.sh` to stop the service and remove the binary, service file, and login entry. The script asks whether to delete the pairing credential. It keeps the credential by default and when run without a terminal. Use `--purge` to delete it or `--keep-token` to keep it without a prompt.

## Publish a release

GitHub Actions builds the Android app and the Linux and macOS companions on pushes to `main` and on pull requests. Download the debug APK and companion from the run's **Artifacts** section. Use the release APK for updates because GitHub Actions can sign debug APKs with a different key on each run.

To publish a release, push a new `vMAJOR.MINOR.PATCH` tag from `main`. For example:

```sh
git tag v0.1.0
git push origin v0.1.0
```

The workflow attaches the signed Android APK, the Linux x86-64 and both macOS companion archives, and `SHA256SUMS.txt` to the GitHub release. Increase the version tag for each release so Android accepts the APK as an update.

If you installed a debug APK, uninstall it before installing the first release APK, then pair again. Later release APKs install over the previous release and keep your pairing.

GitHub Actions signs the release APK with the keystore in `ANDROID_KEYSTORE_BASE64` and unlocks it with `ANDROID_KEYSTORE_PASSWORD`. Back up the PKCS#12 keystore and password. Use the same signing key, with alias `nullptr`, for future updates.
