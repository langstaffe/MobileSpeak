//! Concrete streaming processors. ONNX libraries are loaded only on selection.
use crate::{denoise_packet, NoiseSuppressionMode};
use libloading::Library;
use rubato::{
    Resampler, SincFixedIn, SincInterpolationParameters, SincInterpolationType, WindowFunction,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::VecDeque,
    ffi::{c_char, c_void, CStr, CString},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
pub type Result<T> = std::result::Result<T, String>;
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct Selection {
    pub noise: NoiseSuppressionMode,
}
#[derive(Clone, Copy)]
pub struct Calibration {
    pub onset: f32,
    pub sustain: f32,
    pub attack_samples: usize,
}
const EARSHOT_FRAME_SIZE: usize = 256;
const EARSHOT_CALIBRATION: Calibration = Calibration {
    onset: 0.50,
    sustain: 0.40,
    attack_samples: 768,
};
pub fn remove_retired_model_cache(storage: &Path) {
    // Only files formerly extracted by MobileSpeak; leave identity, chat and other data alone.
    for name in ["dpdfnet8_48khz_hr.onnx", "silero_vad_16k_op15.onnx"] {
        let _ = std::fs::remove_file(storage.join("audio-models-v1").join(name));
    }
}
fn library(_name: &str) -> PathBuf {
    #[cfg(target_os = "android")]
    {
        PathBuf::from(_name)
    }
    #[cfg(target_os = "ios")]
    {
        PathBuf::from("@rpath/SherpaOnnxC.framework/SherpaOnnxC")
    }
    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("vendor/macos/lib")
            .join(_name.replace(".so", ".dylib"))
    }
}
#[repr(C)]
struct DpdfConfig {
    model: *const c_char,
    attenuation: f32,
}
#[repr(C)]
struct ModelConfig {
    gtcrn: *const c_char,
    threads: i32,
    debug: i32,
    provider: *const c_char,
    dpdf: DpdfConfig,
}
#[repr(C)]
struct Denoised {
    samples: *const f32,
    len: i32,
    rate: i32,
}
type Create = unsafe extern "C" fn(*const ModelConfig) -> *const c_void;
type Destroy = unsafe extern "C" fn(*const c_void);
type Run = unsafe extern "C" fn(*const c_void, *const f32, i32, i32) -> *const Denoised;
type Free = unsafe extern "C" fn(*const Denoised);
type Rate = unsafe extern "C" fn(*const c_void) -> i32;
unsafe extern "C" {
    fn ms_dpdf_create(
        create: Create,
        config: *const ModelConfig,
        error: *mut *const c_char,
    ) -> *const c_void;
    fn ms_dpdf_run(
        run: Run,
        model: *const c_void,
        samples: *const f32,
        n: i32,
        error: *mut *const c_char,
    ) -> *const Denoised;
    fn ms_dpdf_reset(reset: Destroy, model: *const c_void) -> i32;
}
struct Dpdf {
    ptr: *const c_void,
    destroy: Destroy,
    reset: Destroy,
    run: Run,
    free: Free,
    _lib: Library,
}
// Owned by exactly one DSP/loader thread at a time. No shared access to the C handle.
unsafe impl Send for Dpdf {}
impl Dpdf {
    fn new(storage: &Path) -> Result<Self> {
        let name = "dpdfnet2_48khz_hr.onnx";
        let bytes = include_bytes!("../models/dpdfnet2_48khz_hr.onnx");
        // Recreate from trusted embedded bytes on the loader, never trust mutable model files.
        let dir = storage.join("audio-models-v1");
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let path = dir.join(name);
        // Each concurrent bridge/loader owns its temporary file; never share a .tmp name.
        static NEXT_FILE: AtomicU64 = AtomicU64::new(0);
        let temporary = dir.join(format!(
            ".{name}.{}-{}.tmp",
            std::process::id(),
            NEXT_FILE.fetch_add(1, Ordering::Relaxed)
        ));
        let written =
            std::fs::write(&temporary, bytes).and_then(|_| std::fs::rename(&temporary, &path));
        if let Err(error) = written {
            let _ = std::fs::remove_file(&temporary);
            return Err(error.to_string());
        }
        let options = dir.join("cpu-options.conf");
        let temporary = dir.join(format!(
            ".cpu-options-{}-{}.tmp",
            std::process::id(),
            NEXT_FILE.fetch_add(1, Ordering::Relaxed)
        ));
        let written = std::fs::write(&temporary, b"SessionConfig.session.intra_op.allow_spinning=0\nSessionConfig.session.inter_op.allow_spinning=0\n").and_then(|_| std::fs::rename(&temporary, &options));
        if let Err(error) = written {
            let _ = std::fs::remove_file(&temporary);
            return Err(error.to_string());
        }
        let provider =
            CString::new(format!("cpu:{}", options.display())).map_err(|e| e.to_string())?;
        let filename =
            CString::new(path.to_string_lossy().as_bytes()).map_err(|e| e.to_string())?;
        unsafe {
            let lib =
                Library::new(library("libsherpa-onnx-c-api.so")).map_err(|e| e.to_string())?;
            let create: Create = *lib
                .get(b"SherpaOnnxCreateOnlineSpeechDenoiser\0")
                .map_err(|e| e.to_string())?;
            let destroy: Destroy = *lib
                .get(b"SherpaOnnxDestroyOnlineSpeechDenoiser\0")
                .map_err(|e| e.to_string())?;
            let reset: Destroy = *lib
                .get(b"SherpaOnnxOnlineSpeechDenoiserReset\0")
                .map_err(|e| e.to_string())?;
            let run: Run = *lib
                .get(b"SherpaOnnxOnlineSpeechDenoiserRun\0")
                .map_err(|e| e.to_string())?;
            let free: Free = *lib
                .get(b"SherpaOnnxDestroyDenoisedAudio\0")
                .map_err(|e| e.to_string())?;
            let rate: Rate = *lib
                .get(b"SherpaOnnxOnlineSpeechDenoiserGetSampleRate\0")
                .map_err(|e| e.to_string())?;
            let config = ModelConfig {
                gtcrn: c"".as_ptr(),
                threads: 2,
                debug: 0,
                provider: provider.as_ptr(),
                dpdf: DpdfConfig {
                    model: filename.as_ptr(),
                    attenuation: 0.0,
                },
            };
            let mut error = std::ptr::null();
            let ptr = ms_dpdf_create(create, &config, &mut error);
            if !error.is_null() {
                return Err(CStr::from_ptr(error).to_string_lossy().into_owned());
            }
            if ptr.is_null() {
                return Err("DPDFNet initialization failed".into());
            }
            let model = Self {
                ptr,
                destroy,
                reset,
                run,
                free,
                _lib: lib,
            };
            if rate(ptr) != 48000 {
                return Err("DPDFNet must output 48000 Hz".into());
            }
            Ok(model)
        }
    }
    fn process(&mut self, input: &[f32]) -> Result<Vec<f32>> {
        unsafe {
            let mut error = std::ptr::null();
            let p = ms_dpdf_run(
                self.run,
                self.ptr,
                input.as_ptr(),
                input.len() as i32,
                &mut error,
            );
            if !error.is_null() {
                return Err(CStr::from_ptr(error).to_string_lossy().into_owned());
            }
            if p.is_null() {
                return Ok(Vec::new());
            }
            let r = &*p;
            let result = if r.rate != 48000
                || r.len < 0
                || r.len > 1920
                || (r.len > 0 && r.samples.is_null())
            {
                Err("Invalid DPDFNet output".into())
            } else if r.len == 0 {
                Ok(Vec::new())
            } else {
                Ok(std::slice::from_raw_parts(r.samples, r.len as usize).to_vec())
            };
            (self.free)(p);
            result
        }
    }
}
impl Drop for Dpdf {
    fn drop(&mut self) {
        unsafe {
            let _ = ms_dpdf_reset(self.destroy, self.ptr);
        }
    }
}
enum Denoiser {
    None,
    Rnnoise(Box<nnnoiseless::DenoiseState<'static>>),
    Dpdf(Dpdf),
}
#[derive(Clone, Debug)]
pub struct Frame {
    pub start: u64,
    pub samples: [i16; 960],
    pub onset_samples: usize,
    pub sustain: bool,
    pub attack_samples: usize,
    pub probability: f32,
}
struct Score {
    start: u64,
    end: u64,
    probability: f32,
}
pub struct Chain {
    pub selection: Selection,
    denoiser: Denoiser,
    detector: Box<earshot::Detector>,
    resampler: SincFixedIn<f32>,
    skip: usize,
    clean: VecDeque<f32>,
    resample_input: VecDeque<f32>,
    vad_input: VecDeque<f32>,
    scores: VecDeque<Score>,
    origin: Option<u64>,
    clean_position: u64,
    vad_position: u64,
}
impl Chain {
    pub fn new(selection: Selection, storage: &Path) -> Result<Self> {
        let denoiser = match selection.noise {
            NoiseSuppressionMode::None => Denoiser::None,
            NoiseSuppressionMode::Rnnoise => Denoiser::Rnnoise(nnnoiseless::DenoiseState::new()),
            NoiseSuppressionMode::Dpdfnet2 => Denoiser::Dpdf(Dpdf::new(storage)?),
        };
        let detector = earshot::Detector::default_boxed();
        let resampler = SincFixedIn::new(
            1.0 / 3.0,
            1.0,
            SincInterpolationParameters {
                sinc_len: 128,
                f_cutoff: 0.95,
                interpolation: SincInterpolationType::Linear,
                oversampling_factor: 128,
                window: WindowFunction::BlackmanHarris2,
            },
            480,
            1,
        )
        .map_err(|e| e.to_string())?;
        let skip = resampler.output_delay();
        Ok(Self {
            selection,
            denoiser,
            detector,
            resampler,
            skip,
            clean: VecDeque::new(),
            resample_input: VecDeque::new(),
            vad_input: VecDeque::new(),
            scores: VecDeque::new(),
            origin: None,
            clean_position: 0,
            vad_position: 0,
        })
    }
    pub fn reset(&mut self) {
        match &mut self.denoiser {
            Denoiser::Rnnoise(s) => *s = nnnoiseless::DenoiseState::new(),
            Denoiser::Dpdf(s) => unsafe {
                let _ = ms_dpdf_reset(s.reset, s.ptr);
            },
            _ => {}
        }
        self.detector.reset();
        self.resampler.reset();
        self.skip = self.resampler.output_delay();
        self.clean.clear();
        self.resample_input.clear();
        self.vad_input.clear();
        self.scores.clear();
        self.origin = None;
        self.clean_position = 0;
        self.vad_position = 0;
    }
    pub fn process(&mut self, start: u64, samples: &[i16; 960]) -> Result<Vec<Frame>> {
        self.origin.get_or_insert(start);
        let clean = match &mut self.denoiser {
            Denoiser::None => samples.iter().map(|v| *v as f32 / 32768.0).collect(),
            Denoiser::Rnnoise(s) => denoise_packet(samples, s)
                .ok_or("Invalid RNNoise output")?
                .0
                .iter()
                .map(|v| *v as f32 / 32768.0)
                .collect(),
            Denoiser::Dpdf(s) => s.process(
                &samples
                    .iter()
                    .map(|v| *v as f32 / 32768.0)
                    .collect::<Vec<_>>(),
            )?,
        };
        if clean.iter().any(|v| !v.is_finite()) {
            return Err("Non-finite denoiser output".into());
        }
        self.clean.extend(&clean);
        self.resample_input.extend(clean);
        while self.resample_input.len() >= 480 {
            let block: Vec<_> = self.resample_input.drain(..480).collect();
            let output = self
                .resampler
                .process(&[block], None)
                .map_err(|e| e.to_string())?;
            let skip = self.skip.min(output[0].len());
            self.skip -= skip;
            self.vad_input.extend(&output[0][skip..]);
        }
        let size = EARSHOT_FRAME_SIZE;
        while self.vad_input.len() >= size {
            let input: Vec<_> = self.vad_input.drain(..size).collect();
            let probability = self.detector.predict_f32(&input);
            if !probability.is_finite() || !(0.0..=1.0).contains(&probability) {
                return Err("Invalid VAD probability".into());
            }
            self.scores.push_back(Score {
                start: self.vad_position,
                end: self.vad_position + size as u64 * 3,
                probability,
            });
            self.vad_position += size as u64 * 3;
        }
        let mut frames = Vec::new();
        let calibration = EARSHOT_CALIBRATION;
        while self.clean.len() >= 960 && self.clean_position + 960 <= self.vad_position {
            let end = self.clean_position + 960;
            let mut onset = 0;
            let mut sustain = false;
            let mut probability = 0.0f32;
            for s in &self.scores {
                let overlap = end
                    .min(s.end)
                    .saturating_sub(self.clean_position.max(s.start));
                if overlap == 0 {
                    continue;
                }
                probability = probability.max(s.probability);
                if s.probability >= calibration.onset {
                    onset += overlap as usize;
                }
                sustain |= s.probability >= calibration.sustain;
            }
            let mut samples = [0; 960];
            for (i, v) in self.clean.drain(..960).enumerate() {
                samples[i] = (v * 32768.0).round().clamp(-32768.0, 32767.0) as i16;
            }
            frames.push(Frame {
                start: self.origin.unwrap() + self.clean_position,
                samples,
                onset_samples: onset,
                sustain,
                attack_samples: calibration.attack_samples,
                probability,
            });
            self.clean_position = end;
            while self.scores.front().is_some_and(|s| s.end <= end) {
                self.scores.pop_front();
            }
        }
        if self.clean.len() > 4800
            || self.vad_input.len() > EARSHOT_FRAME_SIZE
            || self.resample_input.len() > 480
        {
            return Err("Audio processing backlog exceeded 100 ms".into());
        }
        Ok(frames)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;
    #[test]
    fn retired_cache_cleanup_preserves_current_model_and_core_data() {
        let storage =
            std::env::temp_dir().join(format!("mobilespeak-cache-test-{}", std::process::id()));
        let models = storage.join("audio-models-v1");
        std::fs::create_dir_all(&models).unwrap();
        for name in [
            "dpdfnet8_48khz_hr.onnx",
            "silero_vad_16k_op15.onnx",
            "dpdfnet2_48khz_hr.onnx",
        ] {
            std::fs::write(models.join(name), b"fixture").unwrap();
        }
        std::fs::write(storage.join("identity.json"), b"preserved").unwrap();
        remove_retired_model_cache(&storage);
        remove_retired_model_cache(&storage);
        assert!(!models.join("dpdfnet8_48khz_hr.onnx").exists());
        assert!(!models.join("silero_vad_16k_op15.onnx").exists());
        assert!(models.join("dpdfnet2_48khz_hr.onnx").exists());
        assert_eq!(
            std::fs::read(storage.join("identity.json")).unwrap(),
            b"preserved"
        );
        std::fs::remove_dir_all(storage).unwrap();
    }
    #[test]
    fn three_denoisers_with_earshot_are_continuous_and_finite() {
        let speech: Vec<i16> = include_bytes!("../tests/fixtures/speech.pcm")
            .chunks_exact(2)
            .map(|s| i16::from_le_bytes([s[0], s[1]]))
            .collect();
        let storage =
            std::env::temp_dir().join(format!("mobilespeak-model-test-{}", std::process::id()));
        for noise in [
            NoiseSuppressionMode::Dpdfnet2,
            NoiseSuppressionMode::Rnnoise,
            NoiseSuppressionMode::None,
        ] {
            let begin = Instant::now();
            let mut chain = Chain::new(Selection { noise }, &storage).unwrap();
            eprintln!("Init {noise:?}+Earshot: {:?}", begin.elapsed());
            let begin = Instant::now();
            let mut count = 0;
            let mut saw_voice = false;
            for i in 0..200u64 {
                let samples = if i < 50 || i >= 150 {
                    [0; 960]
                } else {
                    std::array::from_fn(|j| speech[((i - 50) as usize * 960 + j) % speech.len()])
                };
                for frame in chain.process(i * 960, &samples).unwrap() {
                    assert_eq!(frame.start, count * 960);
                    assert!(frame.probability.is_finite());
                    assert!((0.0..=1.0).contains(&frame.probability));
                    saw_voice |= frame.probability >= 0.5;
                    if noise == NoiseSuppressionMode::None {
                        let expected = if count < 50 || count >= 150 {
                            [0; 960]
                        } else {
                            std::array::from_fn(|j| {
                                speech[((count - 50) as usize * 960 + j) % speech.len()]
                            })
                        };
                        assert_eq!(frame.samples, expected, "None must pass 48k PCM unchanged");
                    }
                    count += 1;
                }
            }
            assert!(
                (196..=199).contains(&count),
                "unexpected processing latency {count}"
            );
            assert!(saw_voice, "fixture never yielded speech {noise:?}+Earshot");
            assert!(
                matches!(chain.denoiser, Denoiser::None) == (noise == NoiseSuppressionMode::None)
            );
            assert!(
                matches!(chain.denoiser, Denoiser::Rnnoise(_))
                    == (noise == NoiseSuppressionMode::Rnnoise)
            );
            eprintln!(
                "Process {noise:?}+Earshot: {:.2} ms/audio-second, frames={count}",
                begin.elapsed().as_secs_f64() * 1000.0 / 4.0
            );
            chain.reset();
            assert_eq!(
                chain
                    .process(48000, &[0; 960])
                    .unwrap()
                    .first()
                    .map(|f| f.start),
                None
            );
        }
        let _ = std::fs::remove_dir_all(storage);
    }
    #[test]
    fn sinc_resampling_rejects_aliases_and_chunking_is_continuous() {
        fn run(freq: f32, chunk: usize) -> Vec<f32> {
            let mut r = SincFixedIn::new(
                1.0 / 3.0,
                1.0,
                SincInterpolationParameters {
                    sinc_len: 128,
                    f_cutoff: 0.95,
                    interpolation: SincInterpolationType::Linear,
                    oversampling_factor: 128,
                    window: WindowFunction::BlackmanHarris2,
                },
                chunk,
                1,
            )
            .unwrap();
            let mut out = Vec::new();
            for k in 0..48000 / chunk {
                let x: Vec<_> = (k * chunk..(k + 1) * chunk)
                    .map(|i| (2.0 * std::f32::consts::PI * freq * i as f32 / 48000.0).sin())
                    .collect();
                out.extend(r.process(&[x], None).unwrap().remove(0));
            }
            out
        }
        let base = run(1000.0, 480);
        let other = run(1000.0, 960);
        assert_eq!(base.len(), other.len());
        assert!(base.iter().zip(other).all(|(a, b)| (a - b).abs() < 0.0001));
        let high = run(12000.0, 480);
        let rms = |s: &[f32]| (s.iter().map(|x| x * x).sum::<f32>() / s.len() as f32).sqrt();
        assert!(rms(&high[200..]) < rms(&base[200..]) * 0.01);
    }
}

#[cfg(all(test, target_os = "macos"))]
mod resource_tests {
    use super::*;
    fn rss() -> usize {
        String::from_utf8(
            std::process::Command::new("ps")
                .args(["-o", "rss=", "-p", &std::process::id().to_string()])
                .output()
                .unwrap()
                .stdout,
        )
        .unwrap()
        .trim()
        .parse()
        .unwrap()
    }
    #[test]
    fn repeated_model_destruction_has_bounded_residency() {
        let storage =
            std::env::temp_dir().join(format!("mobilespeak-memory-{}", std::process::id()));
        let mut sizes = Vec::new();
        for _ in 0..12 {
            for noise in [
                NoiseSuppressionMode::Dpdfnet2,
                NoiseSuppressionMode::Rnnoise,
                NoiseSuppressionMode::None,
            ] {
                let mut chain = Chain::new(Selection { noise }, &storage).unwrap();
                for i in 0..5 {
                    chain.process(i * 960, &[100; 960]).unwrap();
                }
            }
            sizes.push(rss());
        }
        // Allow allocator/cache warmup, but repeated model residency must not accumulate.
        assert!(
            sizes[11] < sizes[3] + 32 * 1024,
            "model memory kept accumulating: {sizes:?} KiB"
        );
        eprintln!("36 model lifecycle checks, RSS KiB after each 3: {sizes:?}");
        let _ = std::fs::remove_dir_all(storage);
    }
}
