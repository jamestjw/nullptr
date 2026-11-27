# Build the React Native trackpad for Android

Install Node.js 22.13+ or 24.3+, JDK 17, and the Android SDK. Set `JAVA_HOME` and `ANDROID_HOME`. Install Android SDK Platform 36, Build Tools 35.0.0 and 36.0.0, NDK 27.1.12297006, and CMake 3.22.1.

From the repository root, run:

```sh
cd react-native
npm ci
npm run typecheck
cd android
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK includes the JavaScript bundle and runs without Metro. The React Native app builds against the Kotlin transport in `../android` and uses the same companion. It installs alongside the Kotlin app and stores its pairing and settings separately. Open **nullptr RN trackpad** and use the companion's pairing code or QR.
