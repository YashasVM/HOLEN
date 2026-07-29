# Android APK delivery

For physical Android devices, never distribute the emulator APK: it contains only
`x86`/`x86_64` native libraries. Build and provide
`:app:assembleArm64Debug` (`app-arm64-debug.apk`) for modern ARM64 phones,
including the Galaxy S24 Ultra. Use `:app:assembleUniversalDebug` only when a
single multi-ABI APK is explicitly needed. Verify the ABI and output path before
reporting an APK as ready to install.

## APK output location and naming

Save every deliverable APK to `C:\Users\YashasVM\Downloads\code\Sandbox\MAIN\HOLEN\prod\APKs`.
Use an explicit, versioned filename such as `HOLEN-v3.2.0-arm64-debug.apk`; do
not direct users to Gradle's generic `app-arm64-debug.apk` output. Build with a
new Android `versionCode` for each distributed version so devices do not keep
installing an older package. Open the `prod\APKs` folder after creating a
deliverable and report that exact file path.
