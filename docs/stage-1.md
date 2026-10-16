# Stage 1 — CI/build skeleton only

Scope:

- Gradle project for Android API 26+ with `targetSdk 34` for Android 14+ foreground-service type rules.
- No launcher activity and no `MAIN/LAUNCHER` intent filter.
- No capture logic yet.
- Manifest includes the narrow Android-side permission surface needed later for short-lived camera/microphone foreground recording; runtime requests will still be minimal per command.
- GitHub Actions builds the debug APK and uploads it as an artifact without requiring `aapt`, `apktool`, or a checked-in Gradle wrapper JAR.

Expected Stage 1 test:

1. Push this tree to GitHub.
2. The workflow `Build STONX Camera Bridge APK` must finish green.
3. Download `stonx-camera-bridge-debug-apk` and install it only to confirm packaging/installability.
4. Confirm the launcher does **not** show a STONX icon.
5. Do not test camera/microphone yet; components arrive in Stage 2.
