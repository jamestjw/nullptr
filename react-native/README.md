# Build the React Native trackpad for Android

Install Node.js 22.13+ or 24.3+, JDK 17, and the Android SDK. Set `JAVA_HOME` and `ANDROID_HOME`. Install Android SDK Platform 36, Build Tools 35.0.0 and 36.0.0, NDK 27.1.12297006, and CMake 3.22.1.

Run these commands from the React Native app directory, which contains this README and `package-lock.json`:

```sh
npm ci &&
  npm run typecheck &&
  (cd android && ./gradlew :app:assembleDebug)
```

After the build succeeds, install the APK:

```sh
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

The debug APK includes the JavaScript bundle and runs without Metro. Update the desktop companion to a build from the same checkout before pairing. The Kotlin app still works with the updated companion.

Open **nullptr RN trackpad** and use the companion's pairing code or QR. If you used an earlier React Native build, pair again and set your preferences again. This build uses the operating system's secure storage for the pairing credential.

## Generate the iOS project

On macOS with Xcode and CocoaPods, run `npm ci`, then `npx expo prebuild --platform ios` from this directory. Open the generated `.xcworkspace` in Xcode and select your signing team. The generated iOS project and Android host use the same TypeScript app.
