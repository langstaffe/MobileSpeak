# Mobile app versions and Android releases

`AppVersion.xcconfig` is the common user-visible version source. Android reads
`MOBILE_SPEAK_VERSION`; iOS includes the file from `Signing.xcconfig` and both
app configurations use that variable for `MARKETING_VERSION`. Increment Android
`versionCode` and iOS `CURRENT_PROJECT_VERSION` when distributing a new build.
The About pages read their installed package metadata. Rust package versions
are independent.

Publish a **non-draft, non-prerelease** GitHub Release in
`langstaffe/MobileSpeak` with an exact tag such as `v0.3.0`. Its Android APK must
have matching `versionName` (`0.3.0`), an increased `versionCode`, package
`dev.mobilespeak.mobilespeak`, and the existing distribution signing identity.
No signing key or release is created by this implementation.

Preferred asset name: `MobileSpeak-v0.3.0-universal.apk`. This must be a signed,
non-debug APK supporting the advertised devices (currently arm64-v8a and
x86_64). Alternatively use `MobileSpeak-v0.3.0-arm64-v8a.apk`,
`MobileSpeak-v0.3.0-armeabi-v7a.apk`, `MobileSpeak-v0.3.0-x86_64.apk` or
`MobileSpeak-v0.3.0-x86.apk`; the client selects by `SUPPORTED_ABIS` order.
It never takes an arbitrary first `.apk`. Uploaded asset size must be positive;
GitHub `sha256:` digest, when present, must be valid and match the downloaded
file. URLs must point to this repository and that exact release/asset.

Android checks the public Latest Release REST endpoint without a token. It
records the local day before each automatic attempt, even if the request fails.
There are no scheduled background checks or retries. Manual checks join an
in-flight request and report failures explicitly. Automatic prompts are
foreground-only and discarded during unsuitable UI states.

DownloadManager owns background downloads. Only the user's Download action
creates a task, with the exact checked version. The app persists the task ID
and asset metadata, queries once on foreground/completion, then checks size,
optional SHA-256, APK metadata and signing compatibility. Completed system
notifications do **not** open an unverified APK: an app notification opens
About, where the user explicitly chooses Install. If notifications are denied,
the About button remains available. Installation uses a verified private cache
snapshot through FileProvider and the system installer. Unknown-app permission
is requested only after Install; return and tap Install again after granting.
Installation may terminate the app and voice connection.

The native settings pages share their entry row and secondary-page layout.
Android adds settings destinations to the existing saved chat navigation and
uses its 350 ms slide; Compose applies the system animator duration scale.
iOS uses the same native NavigationView as chat and disables its animations
when Reduce Motion is enabled. Settings destinations do not depend on
connection/channel/unread state. Language persistence still has one owner on
each platform; Android route and home scroll state survive locale recreation.

Update foreground state follows Activity resume/pause, separately from the
unchanged voice start/stop lifecycle. This prevents prompts behind system
permission UI. Opening About from a notification consumes the intent so locale
recreation cannot reopen an already dismissed page.

iOS has no update-check, App Store ID or background update behavior.

Local Debug APKs are test artifacts, not formal update packages. A production
APK cannot update a Debug installation signed by a different key. Never ask a
user to uninstall to bypass signature incompatibility. End-to-end formal
upgrade verification requires a real published newer APK and compatible
installed signing identity.
