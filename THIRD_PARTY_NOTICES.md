# Sources and attribution

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

## Noise suppression and voice detection

- DPDFNet2: Ceva-IP/DPDFNet Hugging Face revision
  `dd6818d00f50c836fed43a6243ebe49116de5964`, official
  `dpdfnet2_48khz_hr.onnx`, Apache-2.0.
  https://huggingface.co/Ceva-IP/DPDFNet
- Earshot 1.2.2, released Rust crate, MIT OR Apache-2.0;
  `Detector::predict_f32` consumes 256 samples at 16 kHz.
  https://crates.io/crates/earshot/1.2.2
- sherpa-onnx 1.13.8, Apache-2.0. Native online DPDFNet inference uses
  ONNX Runtime 1.28.2 (MIT), two CPU inference threads
  per selected DPDFNet2 session (spinning disabled). The shipped upstream runtime includes other operator
  kernels; they are not initialized as model sessions.
- rubato 0.16.2: MIT; continuous windowed sinc resampling, 48 kHz to 16 kHz.
  libloading 0.9.0: ISC.
  All transitive Rust revisions/checksums are fixed in `native/Cargo.lock`.

Model and native runtime download URLs and SHA-256 values are recorded in
`native/models/manifest.json`. Models are embedded in the Rust core; the
DPDFNet file required by its C API is written atomically from these trusted
bytes on the loader thread. Native runtime preparation downloads occur only
on the build host. Applications never download inference models.

Additional license texts, including ONNX Runtime ThirdPartyNotices and the
compiled sherpa dependencies, are under `licenses/audio/`.

The sherpa-onnx 1.13.8 streaming DFT is patched to call its existing
kaldi-native-fbank 1.22.3 / KissFFT dependency for FFT/IFFT, using the same
window, overlap, model and state. No neural-network operators are implemented
in MobileSpeak. The patch and pinned source checksum are recorded in
`native/models/manifest.json`; `tool/check_sherpa_fft.py` checks numerical
agreement with the unmodified upstream streaming implementation. Native
transitive dependency versions and archive checksums remain those pinned
in the sherpa-onnx 1.13.8 CMake sources.
