//! Local notification PCM only; never enters the microphone/encoder path.
use std::{
    sync::atomic::{AtomicU32, AtomicU64, Ordering::*},
    time::{Duration, Instant},
};

// Ordinary lifecycle/poll threads only; Android does not expose native stderr in logcat.
pub(super) fn log(message: std::fmt::Arguments<'_>) {
    #[cfg(target_os = "android")]
    {
        #[link(name = "log")]
        extern "C" {
            fn __android_log_write(priority: i32, tag: *const std::ffi::c_char, text: *const std::ffi::c_char) -> i32;
        }
        if let Ok(text) = std::ffi::CString::new(message.to_string()) {
            unsafe { __android_log_write(4, b"MobileSpeakSound\0".as_ptr().cast(), text.as_ptr()); }
        }
    }
    #[cfg(not(target_os = "android"))]
    diagnostic!("{message}");
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u64)]
pub enum Sound {
    SelfEnter = 1,
    MemberEnter,
    MemberLeave,
    SelfDisconnect,
    Alert,
    Message,
}

impl Sound {
    fn wav(self) -> &'static [u8] {
        match self {
            Self::SelfEnter => include_bytes!("../assets/sounds/self_enter.wav"),
            Self::MemberEnter => include_bytes!("../assets/sounds/member_enter.wav"),
            Self::MemberLeave => include_bytes!("../assets/sounds/member_leave.wav"),
            Self::SelfDisconnect => include_bytes!("../assets/sounds/self_disconnect.wav"),
            Self::Alert => include_bytes!("../assets/sounds/alert.wav"),
            Self::Message => include_bytes!("../assets/sounds/message.wav"),
        }
    }

    fn from_word(word: u64) -> Option<Self> {
        match word & KIND {
            1 => Some(Self::SelfEnter),
            2 => Some(Self::MemberEnter),
            3 => Some(Self::MemberLeave),
            4 => Some(Self::SelfDisconnect),
            5 => Some(Self::Alert),
            6 => Some(Self::Message),
            _ => None,
        }
    }

    fn frames(self) -> usize {
        (self.wav().len() - 44) / 2
    }

    fn sample(self, frame: usize) -> f32 {
        let offset = 44 + frame * 2;
        let bytes = self.wav();
        f32::from(i16::from_le_bytes([bytes[offset], bytes[offset + 1]])) / 32768.
    }
}

const KIND: u64 = 7;
const CURSOR_SHIFT: usize = 3;
const CURSOR: u64 = 0x7fff << CURSOR_SHIFT;
const ENABLED: u64 = 1 << 18;
const GENERATION: u64 = 1 << 19;
const GENERATION_MASK: u64 = !(GENERATION - 1);
const OUTPUT_EPOCH: u64 = 1 << 40;
const OUTPUT_MASK: u64 = !(OUTPUT_EPOCH - 1);
const CUE_GENERATION_MASK: u64 = GENERATION_MASK & !OUTPUT_MASK;
const TRANSITION_FRAMES: usize = 48; // 1 ms; joins the previous amplitude without waiting.

fn next_generation(word: u64) -> u64 {
    (word.wrapping_add(GENERATION) & CUE_GENERATION_MASK) | (word & OUTPUT_MASK)
}

pub struct Slot {
    // Kind, cursor and output epoch change atomically: a pause cannot resurrect a sound.
    word: AtomicU64,
    epoch: Instant,
    expires_ns: AtomicU64,
    drain_until_ns: AtomicU64,
    drain_generation: AtomicU64,
    last_sample: AtomicU64, // Output epoch + amplitude; a paused output cannot seed the new fade.
    transition_from: AtomicU32,
    expired_word: AtomicU64,
}

impl Default for Slot {
    fn default() -> Self {
        Self {
            word: AtomicU64::new(0),
            epoch: Instant::now(),
            expires_ns: AtomicU64::new(0),
            drain_until_ns: AtomicU64::new(0),
            drain_generation: AtomicU64::new(0),
            last_sample: AtomicU64::new(0),
            transition_from: AtomicU32::new(0),
            expired_word: AtomicU64::new(0),
        }
    }
}

impl Slot {
    pub fn set_active(&self, active: bool) -> bool {
        self.report_expired();
        let previous = self.word.fetch_update(AcqRel, Acquire, |word| {
            Some(if active {
                word | ENABLED
            } else {
                (word.wrapping_add(OUTPUT_EPOCH) & OUTPUT_MASK) | (word & CUE_GENERATION_MASK)
            })
        }).unwrap();
        if (previous & ENABLED != 0) != active || previous & KIND != 0 && !active {
            log(format_args!("Sound output active={} epoch={} cleared={:?} rendered_frames={}",
                active, self.output_state() >> 40, Sound::from_word(previous).filter(|_| !active),
                (previous & CURSOR) >> CURSOR_SHIFT));
        }
        (previous & ENABLED != 0) != active
    }

    pub fn is_active(&self) -> bool {
        self.word.load(Acquire) & ENABLED != 0
    }

    pub fn output_state(&self) -> u64 {
        self.word.load(Acquire) & (OUTPUT_MASK | ENABLED)
    }

    pub fn play(&self, sound: Sound) {
        let epoch = self.word.load(Acquire) & (GENERATION_MASK | ENABLED);
        self.play_in_epoch(sound, epoch);
    }

    fn play_in_epoch(&self, sound: Sound, epoch: u64) {
        self.report_expired();
        if epoch & ENABLED == 0 {
            log(format_args!("Sound cue={sound:?} outcome=disabled output_epoch={}", epoch >> 40));
            return;
        }
        // A stalled hardware callback must not replay an old event after recovery.
        let lifetime =
            Duration::from_secs_f64(sound.frames() as f64 / 48_000.) + Duration::from_millis(200);
        self.expires_ns.store(
            self.epoch
                .elapsed()
                .saturating_add(lifetime)
                .as_nanos()
                .min(u64::MAX as u128) as u64,
            Release,
        );
        let result = self.word.fetch_update(AcqRel, Acquire, |word| {
            ((word & (GENERATION_MASK | ENABLED)) == epoch)
                .then(|| next_generation(word) | ENABLED | sound as u64)
        });
        match result {
            Ok(previous) => log(format_args!("Sound cue={sound:?} outcome=accepted output_epoch={} replaced={:?} replaced_rendered_frames={}",
                epoch >> 40, Sound::from_word(previous), (previous & CURSOR) >> CURSOR_SHIFT)),
            Err(_) => log(format_args!("Sound cue={sound:?} outcome=stale output_epoch={}", epoch >> 40)),
        }
    }

    pub fn stop(&self) {
        self.report_expired();
        let previous = self.word.fetch_update(AcqRel, Acquire, |word| {
            Some(next_generation(word) | (word & ENABLED))
        }).unwrap();
        if previous & KIND != 0 {
            log(format_args!("Sound stopped cue={:?} rendered_frames={}",
                Sound::from_word(previous), (previous & CURSOR) >> CURSOR_SHIFT));
        }
    }

    pub fn pending(&self) -> bool {
        self.report_expired();
        let word = self.word.load(Acquire);
        if word & KIND == 0 {
            // Keep the system stream alive while its last PCM buffer reaches hardware.
            return word & ENABLED != 0
                && self.drain_generation.load(Acquire) == word & GENERATION_MASK
                && self.epoch.elapsed().as_nanos() < u128::from(self.drain_until_ns.load(Acquire));
        }
        if self.expired() {
            if self
                .word
                .compare_exchange(word, (word & !KIND) & !CURSOR, AcqRel, Acquire).is_ok() {
                self.expired_word.store(word, Release);
                self.report_expired();
            }
            return self.word.load(Acquire) & KIND != 0;
        }
        true
    }

    fn expired(&self) -> bool {
        self.epoch.elapsed().as_nanos() >= u128::from(self.expires_ns.load(Acquire))
    }

    // Expiry may be detected by render; ordinary API/poll calls publish its diagnostic.
    fn report_expired(&self) {
        let word = self.expired_word.swap(0, AcqRel);
        if word & KIND != 0 {
            log(format_args!("Sound expired cue={:?} output_epoch={} rendered_frames={}",
                Sound::from_word(word), word >> 40, (word & CURSOR) >> CURSOR_SHIFT));
        }
    }

    // Only the render owner calls this. A failed CAS means a newer event owns this frame.
    #[cfg(test)]
    pub fn sample(&self) -> f32 {
        self.sample_in_output(self.output_state())
    }

    pub fn sample_in_output(&self, output: u64) -> f32 {
        for _ in 0..2 {
            let word = self.word.load(Acquire);
            if output & ENABLED == 0 || word & (OUTPUT_MASK | ENABLED) != output {
                return 0.;
            }
            let Some(sound) = Sound::from_word(word) else {
                self.last_sample.store((word >> 40) << 32, Relaxed);
                return 0.;
            };
            let frame = ((word & CURSOR) >> CURSOR_SHIFT) as usize;
            if word & ENABLED == 0 || self.expired() {
                if self
                    .word
                    .compare_exchange(word, word & !(KIND | CURSOR), AcqRel, Acquire).is_ok() {
                    self.expired_word.store(word, Release);
                }
                self.last_sample.store((word >> 40) << 32, Relaxed);
                return 0.;
            }
            let next = if frame + 1 == sound.frames() {
                self.drain_until_ns.store(
                    self.epoch
                        .elapsed()
                        .saturating_add(Duration::from_millis(100))
                        .as_nanos()
                        .min(u64::MAX as u128) as u64,
                    Release,
                );
                self.drain_generation.store(word & GENERATION_MASK, Release);
                word & !(KIND | CURSOR)
            } else {
                word + (1 << CURSOR_SHIFT)
            };
            if self
                .word
                .compare_exchange(word, next, AcqRel, Acquire)
                .is_ok()
            {
                if frame == 0 {
                    let previous = self.last_sample.load(Relaxed);
                    self.transition_from
                        .store(if previous >> 32 == word >> 40 { previous as u32 } else { 0 }, Relaxed);
                }
                let raw = sound.sample(frame);
                let sample = if frame < TRANSITION_FRAMES {
                    let from = f32::from_bits(self.transition_from.load(Relaxed));
                    from + (raw - from) * ((frame + 1) as f32 / TRANSITION_FRAMES as f32)
                } else {
                    raw
                };
                self.last_sample.store(((word >> 40) << 32) | u64::from(sample.to_bits()), Relaxed);
                return sample;
            }
        }
        0.
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn embedded_assets_are_exact_mono_48khz_pcm16_wavs() {
        for (sound, frames) in [
            (Sound::SelfEnter, 17280),
            (Sound::MemberEnter, 10800),
            (Sound::MemberLeave, 10800),
            (Sound::SelfDisconnect, 18240),
            (Sound::Alert, 19200),
            (Sound::Message, 8880),
        ] {
            let wav = sound.wav();
            assert_eq!(&wav[..4], b"RIFF");
            assert_eq!(&wav[8..16], b"WAVEfmt ");
            assert_eq!(u32::from_le_bytes(wav[16..20].try_into().unwrap()), 16);
            assert_eq!(u16::from_le_bytes(wav[20..22].try_into().unwrap()), 1);
            assert_eq!(u16::from_le_bytes(wav[22..24].try_into().unwrap()), 1);
            assert_eq!(u32::from_le_bytes(wav[24..28].try_into().unwrap()), 48000);
            assert_eq!(u16::from_le_bytes(wav[32..34].try_into().unwrap()), 2);
            assert_eq!(u16::from_le_bytes(wav[34..36].try_into().unwrap()), 16);
            assert_eq!(&wav[36..40], b"data");
            assert_eq!(
                u32::from_le_bytes(wav[40..44].try_into().unwrap()) as usize,
                frames * 2
            );
            assert_eq!(sound.frames(), frames);
        }
    }

    #[test]
    fn every_new_event_restarts_the_single_slot_and_old_sound_never_resumes() {
        let slot = Slot::default();
        slot.set_active(true);
        for sound in [
            Sound::MemberEnter,
            Sound::MemberEnter,
            Sound::Alert,
            Sound::Message,
        ] {
            slot.play(sound);
            assert_eq!(Sound::from_word(slot.word.load(Acquire)), Some(sound));
            assert_eq!(slot.word.load(Acquire) & CURSOR, 0);
            for frame in 0..200 {
                let sample = slot.sample();
                if frame >= TRANSITION_FRAMES {
                    assert_eq!(sample, sound.sample(frame));
                }
            }
        }
        for _ in 200..Sound::Message.frames() {
            slot.sample();
        }
        assert_eq!(slot.word.load(Acquire) & KIND, 0);
        assert!(slot.pending()); // PCM already rendered, device tail still draining.
        slot.drain_until_ns.store(0, Release);
        assert!(!slot.pending());
        assert_eq!(slot.sample(), 0.);
    }

    #[test]
    fn stopped_interrupted_and_expired_events_do_not_replay() {
        let slot = Slot::default();
        slot.play(Sound::SelfEnter);
        assert!(!slot.pending());
        slot.set_active(true);
        slot.play(Sound::Alert);
        slot.stop();
        assert!(!slot.pending());
        slot.play(Sound::Alert);
        slot.set_active(false);
        slot.play(Sound::Message);
        slot.set_active(true);
        assert!(!slot.pending());
        assert_eq!(slot.sample(), 0.);
        slot.play(Sound::Message);
        slot.expires_ns.store(0, Release);
        assert!(!slot.pending());
        assert_eq!(slot.sample(), 0.);
    }

    #[test]
    fn interruption_and_resume_cannot_publish_a_pre_interruption_event() {
        let slot = Slot::default();
        slot.set_active(true);
        let old_epoch = slot.word.load(Acquire) & (GENERATION_MASK | ENABLED);
        slot.set_active(false);
        slot.set_active(true);
        slot.play_in_epoch(Sound::Alert, old_epoch);
        assert!(!slot.pending());
        assert_eq!(slot.sample(), 0.);
        slot.play(Sound::Message);
        assert_eq!(
            Sound::from_word(slot.word.load(Acquire)),
            Some(Sound::Message)
        );
    }

    #[test]
    fn old_render_cannot_consume_a_cue_after_output_resumes() {
        let slot = Slot::default();
        slot.set_active(true);
        let old_output = slot.output_state();
        slot.play(Sound::SelfEnter);
        slot.set_active(false);
        slot.set_active(true);
        slot.play(Sound::Message);
        for _ in 0..19200 { assert_eq!(slot.sample_in_output(old_output), 0.); }
        assert_eq!(slot.word.load(Acquire) & CURSOR, 0);
        assert_eq!(Sound::from_word(slot.word.load(Acquire)), Some(Sound::Message));
        slot.sample_in_output(slot.output_state());
        assert_eq!((slot.word.load(Acquire) & CURSOR) >> CURSOR_SHIFT, 1);
    }

    #[test]
    fn cue_generation_wrap_does_not_change_output_epoch() {
        let slot = Slot::default();
        slot.word.store(3 * OUTPUT_EPOCH | ENABLED | CUE_GENERATION_MASK, Release);
        let output = slot.output_state();
        slot.play(Sound::SelfEnter);
        assert_eq!(slot.output_state(), output);
        assert_eq!(slot.word.load(Acquire) & CUE_GENERATION_MASK, 0);
    }
}
