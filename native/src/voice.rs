//! Shared transmit activity. Capture/AEC keep running while this gate is closed.
use crate::audio_models::Frame;
use audiopus::{
    coder::{Encoder, GenericCtl},
    Application, Channels, SampleRate,
};
use std::{
    collections::VecDeque,
    time::{Duration, Instant},
};
use tsproto_packets::packets::CodecType;

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
const HOLD: Duration = Duration::from_millis(200);
const HOLD_SAMPLES: u64 = 9600; // 200ms of the 48k sending timeline.
                                // Five 20ms frames, including the trigger frame, preserve onset and RNNoise's
                                // overlap delay without adding a delay to an already active stream.
const PRE_ROLL: usize = 5;

pub(crate) struct VoiceSender {
    encoder: Encoder,
    pending: VecDeque<[i16; 960]>,
    last_voice: Option<Instant>,
    last_frame: Option<Instant>,
    last_voice_sample: Option<u64>,
    last_frame_sample: Option<u64>,
    attack: usize,
    codec: Option<CodecType>,
}
impl VoiceSender {
    pub fn new() -> Result<Self> {
        Ok(Self {
            encoder: Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip)?,
            pending: VecDeque::with_capacity(PRE_ROLL),
            last_voice: None,
            last_frame: None,
            last_voice_sample: None,
            last_frame_sample: None,
            attack: 0,
            codec: None,
        })
    }
    pub fn speaking(&self) -> bool {
        self.codec.is_some()
    }

    pub fn process(
        &mut self,
        detected: Frame,
        codec: CodecType,
        now: Instant,
        mut send: impl FnMut(CodecType, &[u8]) -> Result<()>,
    ) -> Result<()> {
        debug_assert!(detected.probability.is_finite());
        self.expire(now, &mut send)?;
        if self
            .last_voice_sample
            .is_some_and(|last| detected.start.saturating_sub(last) >= HOLD_SAMPLES)
        {
            self.finish(&mut send)?;
        }
        if !self.speaking()
            && self
                .last_frame
                .is_some_and(|last| now.saturating_duration_since(last) >= HOLD)
            || (!self.speaking()
                && self
                    .last_frame_sample
                    .is_some_and(|last| detected.start.saturating_sub(last) >= HOLD_SAMPLES))
        {
            self.attack = 0;
            self.pending.clear();
        }
        self.last_frame = Some(now);
        self.last_frame_sample = Some(detected.start);
        if self.speaking() {
            if detected.sustain {
                self.last_voice = Some(now);
                self.last_voice_sample = Some(detected.start);
            }
        } else {
            self.attack = if detected.onset_samples > 0 {
                self.attack + detected.onset_samples
            } else {
                0
            };
            if self.attack >= detected.attack_samples {
                self.last_voice = Some(now);
                self.last_voice_sample = Some(detected.start);
            }
        }
        if self.pending.len() == PRE_ROLL {
            self.pending.pop_front();
        }
        self.pending.push_back(detected.samples);
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
        self.last_voice_sample = None;
        self.last_frame_sample = None;
        self.attack = 0;
        self.pending.clear();
        // Take first: even on a transport error we never keep a stale green ring
        // or retry the end marker on every subsequent silent frame.
        if let Some(codec) = self.codec.take() {
            send(codec, &[])?;
        }
        Ok(())
    }

    #[cfg(test)]
    fn process_detected(
        &mut self,
        samples: [i16; 960],
        probability: f32,
        codec: CodecType,
        now: Instant,
        send: impl FnMut(CodecType, &[u8]) -> Result<()>,
    ) -> Result<()> {
        self.process(
            Frame {
                start: 0,
                samples,
                onset_samples: if probability >= 0.85 { 960 } else { 0 },
                sustain: probability >= 0.65,
                attack_samples: 1920,
                probability,
            },
            codec,
            now,
            send,
        )
    }

    pub fn switched(&mut self) {
        self.pending.clear();
        self.attack = 0;
    }

    pub fn interrupt(&mut self, send: impl FnMut(CodecType, &[u8]) -> Result<()>) -> Result<()> {
        let result = self.finish(send);
        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn frame(start: u64, onset: usize, sustain: bool) -> Frame {
        Frame {
            start,
            samples: [100; 960],
            onset_samples: onset,
            sustain,
            attack_samples: 768,
            probability: 0.9,
        }
    }
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
    #[test]
    fn onset_preroll_pause_hold_single_end_and_failed_send() {
        let mut sender = VoiceSender::new().unwrap();
        let start = Instant::now();
        let mut packets = Vec::new();
        for i in 0..15 {
            let f = frame(i * 960, if i == 4 { 768 } else { 0 }, i == 4 || i == 10);
            sender
                .process(
                    f,
                    CodecType::OpusVoice,
                    start + Duration::from_millis(i * 20),
                    |_, data| {
                        packets.push(data.to_vec());
                        Ok(())
                    },
                )
                .unwrap();
            assert_eq!(sender.speaking(), i >= 4);
        }
        assert_eq!(packets.len(), 15); // exactly five pre-roll and ten subsequent packets
        sender
            .expire(start + Duration::from_millis(399), |_, _| {
                panic!("early end")
            })
            .unwrap();
        sender
            .expire(start + Duration::from_millis(400), |_, p| {
                assert!(p.is_empty());
                packets.push(p.to_vec());
                Ok(())
            })
            .unwrap();
        sender
            .expire(start + Duration::from_millis(450), |_, _| {
                panic!("end repeated")
            })
            .unwrap();
        assert!(!sender.speaking());
        sender
            .process(
                frame(0, 768, true),
                CodecType::OpusVoice,
                start + Duration::from_secs(1),
                |_, _| Err("blocked".into()),
            )
            .unwrap_err();
        assert!(!sender.speaking());
        sender
            .interrupt(|_, _| panic!("failed start claimed speech"))
            .unwrap();
    }
    #[test]
    fn tail_uses_audio_duration_even_when_frames_arrive_in_a_batch() {
        let mut sender = VoiceSender::new().unwrap();
        let now = Instant::now();
        let mut real = 0;
        let mut ends = 0;
        for i in 0..14 {
            sender
                .process(
                    frame(i * 960, if i == 0 { 768 } else { 0 }, i == 0),
                    CodecType::OpusVoice,
                    now,
                    |_, p| {
                        if p.is_empty() {
                            ends += 1;
                        } else {
                            real += 1;
                        }
                        Ok(())
                    },
                )
                .unwrap();
            assert_eq!(sender.speaking(), i < 10);
        }
        assert_eq!(real, 10);
        assert_eq!(ends, 1);
    }
    #[test]
    fn switching_preserves_activity_but_drops_unsent_preroll() {
        let mut sender = VoiceSender::new().unwrap();
        let now = Instant::now();
        sender
            .process(
                frame(0, 768, true),
                CodecType::OpusVoice,
                now,
                |_, _| Ok(()),
            )
            .unwrap();
        assert!(sender.speaking());
        sender.switched();
        assert!(sender.speaking());
        sender
            .process(
                frame(960, 0, true),
                CodecType::OpusVoice,
                now + Duration::from_millis(20),
                |_, p| {
                    assert!(!p.is_empty());
                    Ok(())
                },
            )
            .unwrap();
        sender
            .interrupt(|_, p| {
                assert!(p.is_empty());
                Err("disconnected".into())
            })
            .unwrap_err();
        assert!(!sender.speaking());
        sender.interrupt(|_, _| panic!("repeated end")).unwrap();
    }
}
