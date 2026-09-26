//! Shared transmit activity. Capture/AEC keep running while this gate is closed.
use crate::{denoise_packet, NoiseSuppressionMode};
use audiopus::{
    coder::{Encoder, GenericCtl},
    Application, Channels, SampleRate,
};
use nnnoiseless::DenoiseState;
use std::{
    collections::VecDeque,
    panic::{catch_unwind, AssertUnwindSafe},
    time::{Duration, Instant},
};
use tsproto_packets::packets::CodecType;

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
const HOLD: Duration = Duration::from_millis(200);
// Five 20ms frames, including the trigger frame, preserve onset and RNNoise's
// overlap delay without adding a delay to an already active stream.
const PRE_ROLL: usize = 5;

pub(crate) struct VoiceSender {
    detector: Box<DenoiseState<'static>>,
    encoder: Encoder,
    pending: VecDeque<[i16; 960]>,
    last_voice: Option<Instant>,
    last_frame: Option<Instant>,
    attack: u8,
    codec: Option<CodecType>,
}
impl VoiceSender {
    pub fn new() -> Result<Self> {
        Ok(Self {
            detector: catch_unwind(DenoiseState::new)
                .map_err(|_| "Voice detector initialization failed")?,
            encoder: Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip)?,
            pending: VecDeque::with_capacity(PRE_ROLL),
            last_voice: None,
            last_frame: None,
            attack: 0,
            codec: None,
        })
    }
    pub fn speaking(&self) -> bool {
        self.codec.is_some()
    }

    pub fn process(
        &mut self,
        samples: &[i16],
        mode: NoiseSuppressionMode,
        codec: CodecType,
        now: Instant,
        send: impl FnMut(CodecType, &[u8]) -> Result<()>,
    ) -> Result<()> {
        // Detection is always enabled, even when the user chooses unprocessed audio.
        let (clean, probability) = catch_unwind(AssertUnwindSafe(|| {
            denoise_packet(samples, &mut self.detector)
        }))
        .map_err(|_| "Voice detector processing failed")?
        .ok_or("Invalid voice detector output")?;
        let frame = if mode == NoiseSuppressionMode::Rnnoise {
            clean
        } else {
            samples.try_into()?
        };
        self.process_detected(frame, probability, codec, now, send)
    }

    fn process_detected(
        &mut self,
        frame: [i16; 960],
        probability: f32,
        codec: CodecType,
        now: Instant,
        mut send: impl FnMut(CodecType, &[u8]) -> Result<()>,
    ) -> Result<()> {
        self.expire(now, &mut send)?;
        if !self.speaking()
            && self
                .last_frame
                .is_some_and(|last| now.saturating_duration_since(last) >= HOLD)
        {
            self.attack = 0;
            self.pending.clear();
        }
        self.last_frame = Some(now);
        // Require 40ms of confident recurrent/spectral evidence to reject noise
        // transients. The pre-roll recovers onset; hysteresis preserves weak tails.
        if self.speaking() {
            if probability >= 0.65 {
                self.last_voice = Some(now);
            }
        } else {
            self.attack = if probability >= 0.85 {
                self.attack + 1
            } else {
                0
            };
            if self.attack >= 2 {
                self.last_voice = Some(now);
            }
        }
        if self.pending.len() == PRE_ROLL {
            self.pending.pop_front();
        }
        self.pending.push_back(frame);
        if self.last_voice.is_none() {
            return Ok(());
        }
        if !self.speaking() {
            self.encoder.reset_state()?;
        }
        while let Some(frame) = self.pending.pop_front() {
            let mut encoded = [0u8; 1275];
            let len = self.encoder.encode(&frame, &mut encoded)?;
            send(codec, &encoded[..len])?;
            // Publish activity only after the connection accepted a real packet.
            self.codec = Some(codec);
        }
        Ok(())
    }

    pub fn expire(
        &mut self,
        now: Instant,
        send: impl FnMut(CodecType, &[u8]) -> Result<()>,
    ) -> Result<()> {
        if self
            .last_voice
            .is_some_and(|last| now.saturating_duration_since(last) >= HOLD)
        {
            self.finish(send)?;
        }
        Ok(())
    }

    fn finish(&mut self, mut send: impl FnMut(CodecType, &[u8]) -> Result<()>) -> Result<()> {
        self.last_voice = None;
        self.last_frame = None;
        self.attack = 0;
        self.pending.clear();
        // Take first: even on a transport error we never keep a stale green ring
        // or retry the end marker on every subsequent silent frame.
        if let Some(codec) = self.codec.take() {
            send(codec, &[])?;
        }
        Ok(())
    }

    pub fn interrupt(&mut self, send: impl FnMut(CodecType, &[u8]) -> Result<()>) -> Result<()> {
        let result = self.finish(send);
        self.detector =
            catch_unwind(DenoiseState::new).map_err(|_| "Voice detector initialization failed")?;
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn silence_onset_short_pause_tail_and_single_end() {
        let mut sender = VoiceSender::new().unwrap();
        let start = Instant::now();
        let mut packets = Vec::new();
        let mut send = |_, data: &[u8]| {
            packets.push(data.to_vec());
            Ok(())
        };
        for ms in [0, 20, 40] {
            sender
                .process_detected(
                    [0; 960],
                    0.0,
                    CodecType::OpusVoice,
                    start + Duration::from_millis(ms),
                    &mut send,
                )
                .unwrap();
            assert!(!sender.speaking());
        }
        sender
            .process_detected(
                [100; 960],
                0.95,
                CodecType::OpusVoice,
                start + Duration::from_millis(40),
                &mut send,
            )
            .unwrap();
        sender
            .process_detected(
                [100; 960],
                0.95,
                CodecType::OpusVoice,
                start + Duration::from_millis(60),
                &mut send,
            )
            .unwrap();
        assert!(sender.speaking());
        for ms in (80..240).step_by(20) {
            sender
                .process_detected(
                    [0; 960],
                    0.0,
                    CodecType::OpusVoice,
                    start + Duration::from_millis(ms),
                    &mut send,
                )
                .unwrap();
            assert!(sender.speaking());
        }
        sender
            .process_detected(
                [100; 960],
                0.7,
                CodecType::OpusVoice,
                start + Duration::from_millis(240),
                &mut send,
            )
            .unwrap();
        sender
            .expire(start + Duration::from_millis(439), &mut send)
            .unwrap();
        assert!(sender.speaking());
        sender
            .expire(start + Duration::from_millis(440), &mut send)
            .unwrap();
        assert!(!sender.speaking());
        sender
            .expire(start + Duration::from_millis(460), &mut send)
            .unwrap();
        assert_eq!(packets.iter().filter(|p| p.is_empty()).count(), 1);
        assert_eq!(packets.iter().filter(|p| !p.is_empty()).count(), 14); // 5 pre-roll + 8 tail + renewed voice
    }
    #[test]
    fn interruption_clears_preroll_and_speaking_even_if_send_fails() {
        let mut sender = VoiceSender::new().unwrap();
        let now = Instant::now();
        sender
            .process_detected([0; 960], 0.9, CodecType::OpusVoice, now, |_, _| Ok(()))
            .unwrap();
        sender
            .process_detected(
                [0; 960],
                0.9,
                CodecType::OpusVoice,
                now + Duration::from_millis(20),
                |_, _| Ok(()),
            )
            .unwrap();
        assert!(sender.speaking());
        let mut ends = 0;
        assert!(sender
            .interrupt(|_, packet| {
                assert!(packet.is_empty());
                ends += 1;
                Err("disconnected".into())
            })
            .is_err());
        assert!(!sender.speaking());
        sender
            .interrupt(|_, _| {
                ends += 1;
                Ok(())
            })
            .unwrap();
        sender
            .process_detected([0; 960], 0.0, CodecType::OpusVoice, now, |_, _| {
                panic!("stale speech after interruption")
            })
            .unwrap();
        assert_eq!(ends, 1);
    }
    #[test]
    fn real_classifier_silence_noise_and_weak_speech_in_both_modes() {
        let source: Vec<i16> = include_bytes!("../tests/fixtures/speech.pcm")
            .chunks_exact(2)
            .map(|s| i16::from_le_bytes([s[0], s[1]]))
            .collect();
        for mode in [NoiseSuppressionMode::Rnnoise, NoiseSuppressionMode::None] {
            for gain in [1.0, 0.1] {
                let mut sender = VoiceSender::new().unwrap();
                let start = Instant::now();
                let mut ms = 0;
                let mut packets = Vec::new();
                let mut process = |sender: &mut VoiceSender, frame: &[i16]| {
                    sender
                        .process(
                            frame,
                            mode,
                            CodecType::OpusVoice,
                            start + Duration::from_millis(ms),
                            |_, data| {
                                packets.push((ms, data.to_vec()));
                                Ok(())
                            },
                        )
                        .unwrap();
                    ms += 20;
                };
                for _ in 0..50 {
                    process(&mut sender, &[0; 960]);
                    assert!(!sender.speaking());
                }
                // Seeded broadband background, not an all-zero shortcut.
                let mut seed = 7u32;
                for _ in 0..100 {
                    let noise = std::array::from_fn::<_, 960, _>(|_| {
                        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                        (seed >> 16) as i16 / 32
                    });
                    process(&mut sender, &noise);
                    assert!(!sender.speaking(), "noise opened gate: {mode:?}");
                }
                let mut active = 0;
                for chunk in source.as_chunks::<960>().0 {
                    let frame: Vec<i16> = chunk.iter().map(|v| (*v as f32 * gain) as i16).collect();
                    process(&mut sender, &frame);
                    if sender.speaking() {
                        active += 1;
                    }
                }
                // Background after speech must also close the hysteresis gate.
                for _ in 0..100 {
                    let noise = std::array::from_fn::<_, 960, _>(|_| {
                        seed = seed.wrapping_mul(1664525).wrapping_add(1013904223);
                        (seed >> 16) as i16 / 32
                    });
                    process(&mut sender, &noise);
                }
                assert!(!sender.speaking(), "background held gate open: {mode:?}");
                for _ in 0..50 {
                    process(&mut sender, &[0; 960]);
                }
                assert!(!sender.speaking());
                assert!(
                    active > 30,
                    "weak speech missed: {mode:?} gain={gain}, frames={active}"
                );
                assert!(packets.iter().any(|(_, p)| p.is_empty()));
                eprintln!(
                    "VAD fixture {mode:?} gain={gain}: active={active}, first_packet={}ms",
                    packets[0].0 as i64 - 3000
                );
            }
        }
    }
    #[test]
    fn long_pause_starts_a_new_stream_and_failed_end_is_not_retried() {
        let mut sender = VoiceSender::new().unwrap();
        let start = Instant::now();
        let mut packets = Vec::new();
        for ms in [0, 20, 240, 260] {
            sender
                .process_detected(
                    [0; 960],
                    0.95,
                    CodecType::OpusMusic,
                    start + Duration::from_millis(ms),
                    |codec, data| {
                        assert_eq!(codec, CodecType::OpusMusic);
                        packets.push(data.to_vec());
                        Ok(())
                    },
                )
                .unwrap();
        }
        assert!(sender.speaking());
        assert_eq!(packets.iter().filter(|p| p.is_empty()).count(), 1);
        sender
            .expire(start + Duration::from_millis(460), |_, data| {
                assert!(data.is_empty());
                Err("network lost".into())
            })
            .unwrap_err();
        assert!(!sender.speaking());
        sender
            .expire(start + Duration::from_millis(480), |_, _| {
                panic!("end retried")
            })
            .unwrap();
    }

    #[test]
    fn separated_confident_frames_do_not_start_or_replay_stale_audio() {
        let mut sender = VoiceSender::new().unwrap();
        let start = Instant::now();
        for ms in [0, 500, 1000] {
            sender
                .process_detected(
                    [100; 960],
                    0.95,
                    CodecType::OpusVoice,
                    start + Duration::from_millis(ms),
                    |_, _| panic!("isolated evidence sent audio"),
                )
                .unwrap();
            assert!(!sender.speaking());
        }
        let mut packets = 0;
        sender
            .process_detected(
                [100; 960],
                0.95,
                CodecType::OpusVoice,
                start + Duration::from_millis(1020),
                |_, _| {
                    packets += 1;
                    Ok(())
                },
            )
            .unwrap();
        assert_eq!(packets, 2);
    }

    #[test]
    fn standard_end_packet_ends_the_existing_receive_queue_without_extra_hold() {
        use tsclientlib::{audio::AudioHandler, ClientId};
        use tsproto_packets::packets::{AudioData, Direction, InAudioBuf, OutAudio};
        let mut receiver = AudioHandler::<ClientId>::new();
        let mut sender = VoiceSender::new().unwrap();
        let start = Instant::now();
        let mut sequence = 0;
        let mut starts = 0;
        let mut receive = |codec, data: &[u8]| {
            let packet = OutAudio::new(&AudioData::S2C {
                id: sequence,
                from: 7,
                codec,
                data,
            });
            sequence += 1;
            if receiver
                .handle_packet(
                    ClientId(7),
                    InAudioBuf::try_new(Direction::S2C, packet.into_vec())?,
                )?
                .is_some()
            {
                starts += 1;
            }
            Ok(())
        };
        for ms in [0, 20, 40] {
            sender
                .process_detected(
                    [0; 960],
                    0.95,
                    CodecType::OpusVoice,
                    start + Duration::from_millis(ms),
                    &mut receive,
                )
                .unwrap();
        }
        sender
            .expire(start + Duration::from_millis(240), &mut receive)
            .unwrap();
        assert!(!sender.speaking());
        assert_eq!(starts, 1);
        let mut stops = Vec::new();
        for _ in 0..8 {
            stops.extend(receiver.fill_buffer(&mut [0.0; 1920]));
        }
        assert_eq!(stops, vec![ClientId(7)]);
        assert!(receiver.get_queues().is_empty());
    }

    #[test]
    fn failed_start_never_claims_to_be_speaking() {
        let mut sender = VoiceSender::new().unwrap();
        sender
            .process_detected(
                [0; 960],
                0.9,
                CodecType::OpusVoice,
                Instant::now(),
                |_, _| Ok(()),
            )
            .unwrap();
        assert!(sender
            .process_detected(
                [0; 960],
                0.9,
                CodecType::OpusVoice,
                Instant::now(),
                |_, _| Err("send failed".into())
            )
            .is_err());
        assert!(!sender.speaking());
    }
}
