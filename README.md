# Sunpath (Android)

Tracks the sun's position, counts down daylight left (or time until sunrise at night),
and draws a live sky that follows the current weather.

The app is a small native Android shell (`MainActivity.java`) around the bundled page in
`app/src/main/assets/index.html`. It asks for location permission on first launch and pulls
live weather from Open-Meteo (free, no API key).

## Build option A: Android Studio
1. Open this folder in Android Studio (File > Open).
2. Let Gradle sync, then plug in your phone (USB debugging on) and press Run.
   Or use Build > Build App Bundle(s) / APK(s) > Build APK(s) and copy
   `app/build/outputs/apk/debug/app-debug.apk` to your phone.

## Build option B: GitHub (no computer setup)
Push this folder to a GitHub repo. The workflow in `.github/workflows/build-apk.yml`
builds the APK and attaches `Sunpath.apk` to a new Release. Open the repo's Releases page
on your phone, download `Sunpath.apk`, and install it (allow installs from your browser
when Android asks).

## Command line
`./gradlew assembleDebug` (needs JDK 17 and the Android SDK).
