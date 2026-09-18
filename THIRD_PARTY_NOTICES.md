# Sources and attribution

- The small-screen theme, avatar, channel card and shell design were adapted from
  the user-provided `OpenSpeak-origin/clients/openspeak_flutter` project. Its
  networking and large-screen shell are not included.
- ReSpeak tsclientlib / tsproto and associated crates:
  https://github.com/ReSpeak/tsclientlib at
  `ee3bc6f45a7137db7793ba5593a321df400d53e5`.
  MIT OR Apache-2.0; license texts are in `licenses/`.
  `native/src/badges.json` is generated from upstream `Badges.csv`.
- libopus, bundled through audiopus_sys: BSD-3-Clause; license text is in `licenses/`.
- nnnoiseless 0.5.2, a Rust port of RNNoise: BSD-3-Clause; license text is in
  `licenses/nnnoiseless-BSD.txt`.
- AndroidX, Jetpack Compose and Kotlin components use their respective upstream
  licenses. Versions are pinned by the Android Gradle build.
- Other Rust dependencies are pinned in `native/Cargo.lock` and retain their
  respective upstream licenses.

No TeamSpeak official client binaries, official SDK, WebView, RNNoise C library,
OpenSpeak server implementation or server credentials are included.
