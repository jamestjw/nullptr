# Use your Android phone as a Fedora trackpad

1. Connect the phone and computer to the same Tailscale network.
2. Install `android/build/outputs/apk/debug/android-debug.apk` on the phone. With USB debugging, you can use `adb install -r android/build/outputs/apk/debug/android-debug.apk` instead.
3. Run the companion from a terminal in your X11 desktop session:

   ```sh
   ./companion/build/nullptr-companion
   ```

4. Open **nullptr trackpad** on the phone. Tap **Scan pairing QR**, or enter the Tailscale address and pairing code printed by the companion.
5. Keep the companion running while you use the trackpad. Tap to click, or hold then slide to drag. Slide two fingers to scroll, or tap with two fingers to right-click.

Open **Settings** on the trackpad screen to adjust pointer sensitivity or invert two-finger scrolling.

The companion listens on its Tailscale IPv4 address on TCP port `47822` and relies on Tailscale to encrypt traffic. If the phone cannot connect, check that your tailnet policy allows the phone to reach that port. Pair again by restarting the companion with `--pair`.

## Build both apps

Use JDK 17 or 21. Set `ANDROID_HOME` to an Android SDK with platform 35 and build tools 35.0.0.

```sh
./gradlew :android:assembleDebug
```

On Fedora, install Go 1.25 or later, GCC, pkg-config, `libX11-devel`, and `libXtst-devel`. From the project root, run:

```sh
mkdir -p companion/build
(cd companion && go build -o build/nullptr-companion .)
```

The companion currently supports Linux desktops running X11. A future macOS companion can reuse the Android app and its pairing and input protocol.

## Start the companion at login

On Fedora x86-64, make sure Tailscale, `curl`, and `jq` are installed. Run the installer from your X11 desktop session as your regular user:

```sh
bash install-companion.sh
```

The installer verifies the latest stable companion archive against the GitHub release checksum. It installs the binary in `~/.local/bin` and registers a systemd user service. It starts the service now if your phone is already paired and the port is free. An XDG autostart entry starts it on subsequent desktop logins. Fedora's default i3 setup runs these entries through `dex-autostart`.

Rerun the installer to update. Your phone's pairing and firewall settings stay in place. If you started the old companion in a terminal, stop it with Ctrl+C, then run `systemctl --user start nullptr-companion.service`.

For a fresh pairing, stop the service and run `~/.local/bin/nullptr-companion --pair` in your X11 terminal. Scan the QR code or enter the printed details. Press Ctrl+C after pairing, then start the service again.

Check the service with `systemctl --user status nullptr-companion.service`. Read errors with `journalctl --user -u nullptr-companion.service`.

## Publish a release

GitHub Actions builds both apps for pushes to `main` and pull requests. Download the debug APK and Linux companion from the workflow run's **Artifacts** section. Use the release APK for regular updates. CI debug APKs can use a different signing key on each run.

To publish a [GitHub Release](https://github.com/jamestjw/nullptr/releases), push a new `vMAJOR.MINOR.PATCH` tag from `main`, for example:

```sh
git tag v0.1.0
git push origin v0.1.0
```

The workflow attaches the signed Android APK, the Linux x86-64 companion archive, and `SHA256SUMS.txt`. Use an increasing version tag so Android accepts the APK as an update.

If you installed a debug APK, uninstall it before installing the first release APK, then pair again. Later release APKs install over the previous release and keep your pairing.

The workflow uses the `ANDROID_KEYSTORE_BASE64` and `ANDROID_KEYSTORE_PASSWORD` repository secrets. Keep a backup of the PKCS#12 keystore and its password. The key alias is `nullptr`. Future Android updates must use the same key.
