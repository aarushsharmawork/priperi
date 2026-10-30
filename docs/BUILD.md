# Build the Android app

[Overview](../README.md) · [Developer roadmap](PIPELINE.md) · [File index](FILE-INDEX.md)

Run commands from the repository root unless a step says otherwise. On Windows, use `priperi\gradlew.bat -p priperi` in place of `./priperi/gradlew -p priperi`.

## Install the tools

Use JDK 21, Python 3, Android Studio or the Android command-line SDK, and Git. Install Android platform 37, build-tools 36.0.0 and platform-tools through the SDK manager. The app compiles against SDK 37, targets 36 and supports Android 8.0/API 26 or newer. The checked-in wrapper downloads Gradle 9.5.0 and verifies its recorded checksum. Java source compatibility is 17; the build runs on JDK 21.

Set `JAVA_HOME` to your JDK 21 folder and `ANDROID_HOME` to your SDK folder. Put the SDK's `platform-tools`, `cmdline-tools/latest/bin` and `build-tools/36.0.0` folders on PATH. Accept the SDK licenses yourself. For example, with `sdkmanager` already available:

```sh
sdkmanager "platform-tools" "platforms;android-37" "build-tools;36.0.0"
sdkmanager --licenses
```

If you prefer, create ignored `priperi/local.properties` with `sdk.dir=/absolute/path/to/your/Android/sdk`. Do not commit a machine-specific SDK path.

## Fill the app configuration

Follow [Configuration](CONFIGURATION.md). Set `priperiBackendUrl` and `priperiSupabaseKey` in `priperi/gradle.properties`, and replace the five Firebase values in `priperi/app/src/main/res/values/secrets.xml` with values from your own registered Android app. These Firebase identifiers and the Supabase publishable key are public app configuration, not server credentials. They were removed from this export to avoid connecting builds to the original projects.

The project uses Firebase XML resources directly. There is no Google Services Gradle plugin and no need to add a service-account JSON file or a `google-services.json` file to make this build work. Use a Firebase Android registration for `com.priperi` for release, and `com.priperi.debug` for debug/secureTest; use the matching resource values for the variant you build.

Empty backend fields still permit compilation, but the app cannot use a live backend until configured. Firebase placeholders are for editing, not a working runtime setup.

## Build and check

```sh
./priperi/gradlew -p priperi :app:testDebugUnitTest :app:lintSecureTest :app:lintRelease :app:assembleSecureTest :app:assembleRelease
```

Outputs:

- `priperi/app/build/outputs/apk/secureTest/app-secureTest.apk`: optimized, signed with your local Android development key, non-debuggable, package `com.priperi.debug`.
- `priperi/app/build/outputs/apk/release/app-release-unsigned.apk`: optimized unsigned production package `com.priperi`.

Android Studio can also open the `priperi/` directory. Select the build variant explicitly. Normal variants reject detected emulators, root and VPNs. The private emulator fixture uses a separate debug-only build flag; do not distribute that APK.

## Sign a release

Keep your key outside the repository. If this is your first release, create your own key; otherwise use the key for your existing published app. The commands prompt for passwords, so the passwords do not need to appear in the command or source.

```sh
keytool -genkeypair -v -keystore /private/path/priperi-release.jks -alias priperi -keyalg RSA -keysize 3072 -validity 10000
mkdir -p artifacts
zipalign -v -p 4 priperi/app/build/outputs/apk/release/app-release-unsigned.apk artifacts/priperi-aligned.apk
apksigner sign --ks /private/path/priperi-release.jks --ks-key-alias priperi --out artifacts/Priperi-release.apk artifacts/priperi-aligned.apk
apksigner verify --verbose --print-certs artifacts/Priperi-release.apk
```

Do not modify the APK after signing. Keep an offline copy of the key and passwords. A different key cannot update an existing installation with the same package name. Do not uninstall an existing user's app to get around this: uninstalling removes its private keys and local chats. Arrange an account move or an activated recovery kit first.

This recreates the app from source, not a byte-for-byte copy of an old APK. Your configuration, signing certificate, SDK tools and build environment can change its bytes.

Official references: [Android command-line builds](https://developer.android.com/build/building-cmdline) and [APK signing](https://developer.android.com/tools/apksigner).
