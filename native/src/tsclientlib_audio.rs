// SPDX-License-Identifier: MIT OR Apache-2.0
// ReSpeak tsclientlib/src/audio.rs, revision ee3bc6f45a7137db7793ba5593a321df400d53e5.
// https://github.com/ReSpeak/tsclientlib/blob/ee3bc6f45a7137db7793ba5593a321df400d53e5/tsclientlib/src/audio.rs
// Licenses: licenses/tsclientlib-MIT.txt and licenses/tsclientlib-APACHE.txt.
// Local changes: ClientId import; omit disabled audiopus-unstable code and upstream
// test harness; keep newly queued packets alive past the historical PLC cutoff;
// smooth queue catch-up and expose receive diagnostics. Queue thresholds,
// reordering, FEC/PLC and end-marker behavior otherwise stay upstream.
//! Handle receiving audio.
//!
//! The [`AudioHandler`] collects all incoming audio packets and queues them per
//! client. It decodes the audio, handles out-of-order packets and missing
//! packets. It automatically adjusts the queue length based on the jitter of
//! incoming packets.

use std::cmp::Reverse;
use std::collections::{HashMap, VecDeque};
use std::convert::TryInto;
use std::fmt::Debug;
use std::hash::Hash;

use audiopus::coder::Decoder;
use audiopus::{packet, Channels, SampleRate};
use thiserror::Error;
use tracing::{debug, info_span, trace, warn, Span};
use tsproto_packets::packets::{AudioData, CodecType, InAudioBuf};

use tsclientlib::ClientId;

const SAMPLE_RATE: SampleRate = SampleRate::Hz48000;
const CHANNELS: Channels = Channels::Stereo;
const CHANNEL_NUM: usize = 2;
/// If this amount of packets is lost consecutively, we assume the stream stopped.
const MAX_PACKET_LOSSES: usize = 3;
/// Store the buffer sizes for the last `LAST_BUFFER_SIZE_COUNT` packets.
const LAST_BUFFER_SIZE_COUNT: u8 = 255;
/// The amount of samples to maximally buffer. Equivalent to 0.5 s.
const MAX_BUFFER_SIZE: usize = 48_000 / 2;
/// Maximum number of packets in the queue.
const MAX_BUFFER_PACKETS: usize = 50;
/// Buffer for maximal 0.5 s without playing anything.
const MAX_BUFFER_TIME: usize = 48_000 / 2;
/// Remove one frame per `step` frames when speeding-up.
const SPEED_CHANGE_STEPS: usize = 100;
/// The usual amount of samples in a frame.
///
/// Use 48 kHz, 20 ms frames (50 per second) and mono data (1 channel).
/// This means 1920 samples and 7.5 kiB.
const USUAL_FRAME_SIZE: usize = 48000 / 50;

type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Error)]
#[non_exhaustive]
pub enum Error {
    #[error("Failed to create opus decoder: {0}")]
    CreateDecoder(#[source] audiopus::Error),
    #[error("Opus decode failed: {error} (packet: {packet:?})")]
    Decode {
        #[source]
        error: audiopus::Error,
        packet: Option<Vec<u8>>,
    },
    #[error("Get duplicate packet id {0}")]
    Duplicate(u16),
    #[error("Failed to get packet samples: {0}")]
    GetPacketSample(#[source] audiopus::Error),
    #[error("Audio queue is full, dropping")]
    QueueFull,
    #[error("Audio packet is too late, dropping (wanted {wanted}, got {got})")]
    TooLate { wanted: u16, got: u16 },
    #[error("Packet has too many samples")]
    TooManySamples,
    #[error("Only opus audio is supported, ignoring {0:?}")]
    UnsupportedCodec(CodecType),
}

#[derive(Clone, Debug)]
struct SlidingWindowMinimum<T: Copy + Default + Ord> {
    /// How long a value stays in the sliding window.
    size: u8,
    /// This is a sliding window minimum, it contains
    /// `(insertion time, value)`.
    ///
    /// When we insert a value, we can remove all bigger sample counts,
    /// thus the queue always stays sorted with the minimum at the front
    /// and the maximum at the back (latest entry).
    ///
    /// Provides amortized O(1) minimum.
    /// Source: https://people.cs.uct.ac.za/~ksmith/articles/sliding_window_minimum.html#sliding-window-minimum-algorithm
    queue: VecDeque<(u8, T)>,
    /// The current insertion time.
    cur_time: u8,
}

#[derive(Debug)]
struct QueuePacket {
    packet: InAudioBuf,
    samples: usize,
    id: u16,
}

/// A queue for audio packets for one audio stream.
pub struct AudioQueue {
    span: Span,
    decoder: Decoder,
    pub volume: f32,
    /// The id of the next packet that should be decoded.
    ///
    /// Used to check for packet loss.
    next_id: u16,
    /// If the last packet was a whisper packet.
    whispering: bool,
    packet_buffer: VecDeque<QueuePacket>,
    /// Amount of samples in the `packet_buffer`.
    packet_buffer_samples: usize,
    /// Temporary buffer that contains the samples of one decoded packet.
    decoded_buffer: Vec<f32>,
    /// The number of samples in `decoded_buffer` that have been returned.
    decoded_pos: usize,
    /// The number of samples in the last packet.
    last_packet_samples: usize,
    /// The last `packet_loss_num` packet decodes were a loss.
    packet_loss_num: usize,
    /// The amount of samples to buffer until this queue is ready to play.
    buffering_samples: usize,
    /// The amount of packets in the buffer when a packet was decoded.
    ///
    /// Uses the amount of samples in the `packet_buffer` / `USUAL_PACKET_SAMPLES`.
    /// Used to expand or reduce the buffer.
    last_buffer_size_min: SlidingWindowMinimum<u8>,
    last_buffer_size_max: SlidingWindowMinimum<Reverse<u8>>,
    /// Buffered for this duration.
    buffered_for_samples: usize,
}

/// Handles incoming audio, has one [`AudioQueue`] per sending client.
pub struct AudioHandler<Id: Clone + Debug + Eq + Hash + PartialEq = ClientId> {
    queues: HashMap<Id, AudioQueue>,
    /// Buffer this amount of samples for new queues before starting to play.
    ///
    /// Updated when a new queue gets added.
    avg_buffer_samples: usize,
    diagnostics: ReceiveDiagnostics,
}

// Session-thread counters, summed across streams and preserved when queues end.
// FEC counts decoder requests; a packet need not actually contain FEC data.
#[derive(Default)]
pub struct ReceiveDiagnostics {
    pub plc_frames: u64,
    pub fec_frames: u64,
    pub catchup_frames: u64,
    pub truncated_packets: u64,
    pub buffering_frames: u64,
    pub decode_errors: u64,
}

impl<T: Copy + Default + Ord> SlidingWindowMinimum<T> {
    fn new(size: u8) -> Self {
        Self {
            size,
            queue: Default::default(),
            cur_time: 0,
        }
    }

    fn push(&mut self, value: T) {
        while self
            .queue
            .back()
            .map(|(_, s)| *s >= value)
            .unwrap_or_default()
        {
            self.queue.pop_back();
        }
        let i = self.cur_time;
        self.queue.push_back((i, value));
        while self
            .queue
            .front()
            .map(|(i, _)| self.cur_time.wrapping_sub(*i) >= self.size)
            .unwrap_or_default()
        {
            self.queue.pop_front();
        }
        self.cur_time = self.cur_time.wrapping_add(1);
    }

    fn get_min(&self) -> T {
        self.queue.front().map(|(_, s)| *s).unwrap_or_default()
    }
}

impl AudioQueue {
    fn new(packet: InAudioBuf) -> Result<Self> {
        let data = packet.data().data();
        let opus_packet = data.data().try_into().map_err(Error::GetPacketSample)?;
        let last_packet_samples =
            packet::nb_samples(opus_packet, SAMPLE_RATE).map_err(Error::GetPacketSample)?;
        if last_packet_samples > MAX_BUFFER_SIZE {
            return Err(Error::TooManySamples);
        }

        let last_packet_samples = last_packet_samples * CHANNEL_NUM;
        let whispering = matches!(data, AudioData::S2CWhisper { .. });
        let decoder = Decoder::new(SAMPLE_RATE, CHANNELS).map_err(Error::CreateDecoder)?;

        let mut res = Self {
            span: Span::current(),
            decoder,
            volume: 1.0,
            next_id: data.id(),
            whispering,
            packet_buffer: Default::default(),
            packet_buffer_samples: 0,
            decoded_buffer: Default::default(),
            decoded_pos: 0,
            last_packet_samples,
            packet_loss_num: 0,
            buffering_samples: 0,
            last_buffer_size_min: SlidingWindowMinimum::new(LAST_BUFFER_SIZE_COUNT),
            last_buffer_size_max: SlidingWindowMinimum::<Reverse<u8>>::new(LAST_BUFFER_SIZE_COUNT),
            buffered_for_samples: 0,
        };
        res.add_buffer_size(0);
        res.add_packet(packet)?;
        Ok(res)
    }

    pub fn get_decoder(&self) -> &Decoder {
        &self.decoder
    }
    pub fn is_whispering(&self) -> bool {
        self.whispering
    }

    /// Size is in samples.
    fn add_buffer_size(&mut self, size: usize) {
        if let Ok(size) = (size / USUAL_FRAME_SIZE).try_into() {
            self.last_buffer_size_min.push(size);
            self.last_buffer_size_max.push(Reverse(size));
        } else {
            warn!(parent: &self.span, size, "Failed to put amount of packets into an u8");
        }
    }

    /// The approximate deviation of the buffer size.
    fn get_deviation(&self) -> u8 {
        let min = self.last_buffer_size_min.get_min();
        let max = self.last_buffer_size_max.get_min();
        max.0 - min
    }

    fn add_packet(&mut self, packet: InAudioBuf) -> Result<()> {
        let _span = self.span.enter();
        if self.packet_buffer.len() >= MAX_BUFFER_PACKETS {
            return Err(Error::QueueFull);
        }
        let samples;
        if packet.data().data().data().len() <= 1 {
            // End of stream
            samples = 0;
        } else {
            let opus_packet = packet
                .data()
                .data()
                .data()
                .try_into()
                .map_err(Error::GetPacketSample)?;
            samples =
                packet::nb_samples(opus_packet, SAMPLE_RATE).map_err(Error::GetPacketSample)?;
            if samples > MAX_BUFFER_SIZE {
                return Err(Error::TooManySamples);
            }
        }

        let id = packet.data().data().id();
        let packet = QueuePacket {
            packet,
            samples,
            id,
        };
        if id.wrapping_sub(self.next_id) > MAX_BUFFER_PACKETS as u16 {
            return Err(Error::TooLate {
                wanted: self.next_id,
                got: id,
            });
        }

        // Put into first spot where the id is smaller
        let i = self.packet_buffer.len()
            - self
                .packet_buffer
                .iter()
                .enumerate()
                .rev()
                .take_while(|(_, p)| p.id.wrapping_sub(id) <= MAX_BUFFER_PACKETS as u16)
                .count();
        // Check for duplicate packet
        if let Some(p) = self.packet_buffer.get(i) {
            if p.id == packet.id {
                return Err(Error::Duplicate(p.id));
            }
        }

        trace!("Insert packet {} at {}", id, i);
        let last_id = self
            .packet_buffer
            .back()
            .map(|p| p.id.wrapping_add(1))
            .unwrap_or(id);
        if last_id <= id {
            self.buffering_samples = self.buffering_samples.saturating_sub(samples);
            // Reduce buffering counter by lost packets if there are some
            self.buffering_samples = self
                .buffering_samples
                .saturating_sub(usize::from(id - last_id) * self.last_packet_samples);
        }

        self.packet_buffer_samples += packet.samples;
        self.packet_buffer.insert(i, packet);

        Ok(())
    }

    fn decode_packet(
        &mut self,
        packet: Option<&QueuePacket>,
        fec: bool,
        diagnostics: &mut ReceiveDiagnostics,
    ) -> Result<()> {
        let _span = self.span.clone().entered();
        trace!(has_packet = packet.is_some(), fec, "Decoding packet");
        let packet_data;
        let len;
        if let Some(p) = packet {
            packet_data = Some(
                p.packet
                    .data()
                    .data()
                    .data()
                    .try_into()
                    .map_err(Error::GetPacketSample)?,
            );
            len = p.samples;
            self.whispering = matches!(p.packet.data().data(), AudioData::S2CWhisper { .. });
        } else {
            packet_data = None;
            len = self.last_packet_samples;
        }
        self.packet_loss_num += 1;

        let orig_buffer_len = self.decoded_buffer.len();
        self.decoded_buffer
            .resize(orig_buffer_len + len * CHANNEL_NUM, 0.0);
        let len = self
            .decoder
            .decode_float(
                packet_data,
                (&mut self.decoded_buffer[orig_buffer_len..])
                    .try_into()
                    .map_err(Error::GetPacketSample)?,
                fec,
            )
            .map_err(|e| Error::Decode {
                error: e,
                packet: packet.map(|p| p.packet.raw_data().to_vec()),
            })?;
        self.last_packet_samples = len;
        self.decoded_buffer
            .truncate(orig_buffer_len + len * CHANNEL_NUM);
        if packet.is_none() {
            diagnostics.plc_frames += len as u64;
        } else if fec {
            diagnostics.fec_frames += len as u64;
        }

        // Update packet_loss_num
        if packet.is_some() && !fec {
            self.packet_loss_num = 0;
        }

        // Update last_buffer_size
        let mut count = self.packet_buffer_samples;
        if let Some(last) = self.packet_buffer.back() {
            // Lost packets
            trace!(
                last.id,
                next_id = self.next_id,
                first_id = self.packet_buffer.front().unwrap().id,
                buffer_len = self.packet_buffer.len(),
                "Ids"
            );
            count += (usize::from(last.id.wrapping_sub(self.next_id)) + 1
                - self.packet_buffer.len())
                * self.last_packet_samples;
        }
        self.add_buffer_size(count);

        Ok(())
    }

    /// Decode data and return the requested length of buffered data.
    ///
    /// Returns `true` in the second return value when the stream ended,
    /// `false` when it continues normally.
    fn get_next_data(
        &mut self,
        len: usize,
        diagnostics: &mut ReceiveDiagnostics,
    ) -> Result<(&[f32], bool)> {
        let _span = self.span.clone().entered();
        if self.buffering_samples > 0 {
            if self.buffered_for_samples >= MAX_BUFFER_TIME {
                self.buffering_samples = 0;
                self.buffered_for_samples = 0;
                trace!(
                    buffered_for_samples = self.buffered_for_samples,
                    buffering_samples = self.buffering_samples,
                    "Buffered for too long"
                );
            } else {
                self.buffered_for_samples += len;
                diagnostics.buffering_frames += (len / CHANNEL_NUM) as u64;
                trace!(
                    buffered_for_samples = self.buffered_for_samples,
                    buffering_samples = self.buffering_samples,
                    "Buffering"
                );
                return Ok((&[], false));
            }
        }
        // Need to refill buffer
        self.decoded_buffer.drain(..self.decoded_pos);

        while self.decoded_buffer.len() < len {
            trace!(
                decoded_buffer = self.decoded_buffer.len(),
                len,
                "get_next_data"
            );

            // Decode a packet
            if let Some(packet) = self.packet_buffer.pop_front() {
                if packet.packet.data().data().data().len() <= 1 {
                    // End of stream
                    return Ok((&self.decoded_buffer, true));
                }

                self.packet_buffer_samples -= packet.samples;
                let cur_id = self.next_id;
                self.next_id = self.next_id.wrapping_add(1);
                if packet.id != cur_id {
                    debug_assert!(
                        packet.id.wrapping_sub(cur_id) < MAX_BUFFER_PACKETS as u16,
                        "Invalid packet queue state: {} < {}",
                        packet.id,
                        cur_id
                    );
                    // Packet loss
                    debug!(need = cur_id, have = packet.id, "Audio packet loss");
                    if packet.id == self.next_id {
                        // Can use forward-error-correction
                        self.decode_packet(Some(&packet), true, diagnostics)?;
                    } else {
                        self.decode_packet(None, false, diagnostics)?;
                    }
                    self.packet_buffer_samples += packet.samples;
                    self.packet_buffer.push_front(packet);
                } else {
                    self.decode_packet(Some(&packet), false, diagnostics)?;
                }
            } else {
                debug!("No packets in queue");
                // Packet loss or end of stream
                self.decode_packet(None, false, diagnostics)?;
            }

            if self.last_packet_samples == 0 {
                break;
            }

            // Check if we should speed-up playback
            let min = self.last_buffer_size_min.get_min();
            let dev = self.get_deviation();
            if min > (MAX_BUFFER_SIZE / USUAL_FRAME_SIZE) as u8 {
                debug!(min, "Truncating buffer");
                // Throw out all but min samples
                let mut keep_samples = 0;
                let keep = self
                    .packet_buffer
                    .iter()
                    .rev()
                    .take_while(|p| {
                        keep_samples += p.samples;
                        keep_samples < usize::from(min) + USUAL_FRAME_SIZE
                    })
                    .count();
                let len = self.packet_buffer.len() - keep;
                diagnostics.truncated_packets += len as u64;
                self.packet_buffer.drain(..len);
                self.packet_buffer_samples = self.packet_buffer.iter().map(|p| p.samples).sum();
                if let Some(p) = self.packet_buffer.front() {
                    self.next_id = p.id;
                }
            } else if min > dev {
                // Speed-up
                debug!(
                    min,
                    cur_packet_count = self.packet_buffer.len(),
                    last_packet_samples = self.last_packet_samples,
                    dev,
                    "Speed-up buffer"
                );
                diagnostics.catchup_frames +=
                    speed_up_tail(&mut self.decoded_buffer, self.last_packet_samples) as u64;
            }
        }

        self.decoded_pos = len;
        Ok((&self.decoded_buffer[..len], false))
    }
}

fn speed_up_tail(buffer: &mut Vec<f32>, frames: usize) -> usize {
    let removed = frames / SPEED_CHANGE_STEPS;
    if removed == 0 {
        return 0;
    }
    let start = buffer.len() - frames * CHANNEL_NUM;
    let remaining = frames - removed;
    // Keep packet endpoints and stereo alignment, avoiding periodic hard drops.
    // ponytail: linear interpolation at ~1% catch-up; use a band-limited resampler
    // if larger rate changes are ever needed.
    for frame in 0..remaining {
        let position = frame * (frames - 1);
        let source = position / (remaining - 1);
        let next = (source + 1).min(frames - 1);
        let fraction = (position % (remaining - 1)) as f32 / (remaining - 1) as f32;
        // source >= frame, so writing forwards never overwrites unread input.
        for channel in 0..CHANNEL_NUM {
            let a = buffer[start + source * CHANNEL_NUM + channel];
            let b = buffer[start + next * CHANNEL_NUM + channel];
            buffer[start + frame * CHANNEL_NUM + channel] = a + (b - a) * fraction;
        }
    }
    buffer.truncate(start + remaining * CHANNEL_NUM);
    removed
}

impl<Id: Clone + Debug + Eq + Hash + PartialEq> Default for AudioHandler<Id> {
    fn default() -> Self {
        Self {
            queues: Default::default(),
            avg_buffer_samples: 0,
            diagnostics: Default::default(),
        }
    }
}

impl<Id: Clone + Debug + Eq + Hash + PartialEq> AudioHandler<Id> {
    pub fn new() -> Self {
        Default::default()
    }

    /// Delete all queues
    pub fn reset(&mut self) {
        self.queues.clear();
    }

    pub fn get_queues(&self) -> &HashMap<Id, AudioQueue> {
        &self.queues
    }
    pub fn get_mut_queues(&mut self) -> &mut HashMap<Id, AudioQueue> {
        &mut self.queues
    }

    pub fn take_diagnostics(&mut self) -> ReceiveDiagnostics {
        std::mem::take(&mut self.diagnostics)
    }

    /// `buf` is not cleared before filling it.
    ///
    /// Returns the clients that are not talking anymore.
    pub fn fill_buffer(&mut self, buf: &mut [f32]) -> Vec<Id> {
        self.fill_buffer_with_proc(buf, |_, _| {})
    }

    /// `buf` is not cleared before filling it.
    ///
    /// Same as [`fill_buffer`] but before merging a queue into the output buffer, a preprocessor
    /// function is called. The queue volume is applied after calling the preprocessor.
    ///
    /// Returns the clients that are not talking anymore.
    pub fn fill_buffer_with_proc<F: FnMut(&Id, &[f32])>(
        &mut self,
        buf: &mut [f32],
        mut handle: F,
    ) -> Vec<Id> {
        trace!(len = buf.len(), "Filling audio buffer");
        let mut to_remove = Vec::new();
        for (id, queue) in self.queues.iter_mut() {
            // A delayed burst may arrive after PLC reached the cutoff but before
            // this fill. Decode it instead of deleting freshly accepted packets.
            if queue.packet_loss_num >= MAX_PACKET_LOSSES && queue.packet_buffer.is_empty() {
                debug!(packet_loss_num = queue.packet_loss_num, "Removing talker");
                to_remove.push(id.clone());
                continue;
            }

            let vol = queue.volume;
            match queue.get_next_data(buf.len(), &mut self.diagnostics) {
                Err(error) => {
                    self.diagnostics.decode_errors += 1;
                    warn!(%error, "Failed to decode audio packet");
                }
                Ok((r, is_end)) => {
                    handle(id, r);
                    for i in 0..r.len() {
                        buf[i] += r[i] * vol;
                    }
                    if is_end {
                        to_remove.push(id.clone());
                    }
                }
            }
        }

        for id in &to_remove {
            self.queues.remove(id);
        }
        to_remove
    }

    /// Add a packet to the audio queue.
    ///
    /// If a new client started talking, returns the id of this client.
    pub fn handle_packet(&mut self, id: Id, packet: InAudioBuf) -> Result<Option<Id>> {
        let empty = packet.data().data().data().len() <= 1;
        let codec = packet.data().data().codec();
        if codec != CodecType::OpusMusic && codec != CodecType::OpusVoice {
            return Err(Error::UnsupportedCodec(codec));
        }

        if let Some(queue) = self.queues.get_mut(&id) {
            queue.add_packet(packet)?;
            Ok(None)
        } else {
            if empty {
                return Ok(None);
            }

            let _span = info_span!("audio queue", client = ?id);
            trace!("Adding talker");
            let mut queue = AudioQueue::new(packet)?;
            if !self.queues.is_empty() {
                // Update avg_buffer_samples
                self.avg_buffer_samples = USUAL_FRAME_SIZE
                    + self
                        .queues
                        .values()
                        .map(|q| usize::from(q.last_buffer_size_min.get_min()))
                        .sum::<usize>()
                        / self.queues.len();
            }
            queue.buffering_samples = self.avg_buffer_samples;
            self.queues.insert(id.clone(), queue);
            Ok(Some(id))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn catchup_keeps_stereo_boundaries_without_periodic_sample_jumps() {
        for frames in [0, 1, 99, 100, 960, 1920, 5760, MAX_BUFFER_SIZE] {
            let mut buffer = vec![0.25; 6];
            for i in 0..frames {
                let sample = i as f32 / frames as f32;
                buffer.extend([sample, -sample]);
            }
            let before = buffer.clone();
            let capacity = buffer.capacity();
            let removed = speed_up_tail(&mut buffer, frames);
            assert_eq!(removed, frames / SPEED_CHANGE_STEPS);
            assert_eq!(buffer.len(), before.len() - removed * CHANNEL_NUM);
            assert_eq!(buffer.capacity(), capacity);
            assert_eq!(&buffer[..6], &before[..6]);
            if removed == 0 {
                assert_eq!(buffer, before);
                continue;
            }
            assert_eq!(&buffer[6..8], &before[6..8], "first stereo frame moved");
            assert_eq!(&buffer[buffer.len() - 2..], &before[before.len() - 2..]);
            let step = (frames - 1) as f32 / (frames - removed - 1) as f32 / frames as f32;
            let mut last = 0.;
            for pair in buffer[8..].chunks_exact(CHANNEL_NUM) {
                assert_eq!(pair[0], -pair[1], "stereo phase changed");
                assert!(
                    (pair[0] - last - step).abs() < 0.000001,
                    "periodic waveform jump"
                );
                last = pair[0];
            }
        }
    }

    #[test]
    fn receive_diagnostics_follow_real_decoder_paths_and_survive_queue_end() {
        use audiopus::{coder::Encoder, Application};
        use tsproto_packets::packets::{Direction, OutAudio};
        let encoder = Encoder::new(SAMPLE_RATE, CHANNELS, Application::Audio).unwrap();
        let mut encoded = [0; 1275];
        let length = encoder
            .encode(&[100; USUAL_FRAME_SIZE * CHANNEL_NUM], &mut encoded)
            .unwrap();
        let packet = |id, data: &[u8]| {
            InAudioBuf::try_new(
                Direction::S2C,
                OutAudio::new(&AudioData::S2C {
                    id,
                    from: 7,
                    codec: CodecType::OpusMusic,
                    data,
                })
                .into_vec(),
            )
            .unwrap()
        };
        let mut audio = AudioHandler::<ClientId>::new();
        for id in 0..5 {
            audio
                .handle_packet(ClientId(7), packet(id, &encoded[..length]))
                .unwrap();
        }
        // Enter the existing catch-up branch without waiting 255 decodes for
        // the startup minimum to leave the sliding window.
        let queue = audio.queues.get_mut(&ClientId(7)).unwrap();
        queue.last_buffer_size_min = SlidingWindowMinimum::new(LAST_BUFFER_SIZE_COUNT);
        queue.last_buffer_size_max = SlidingWindowMinimum::new(LAST_BUFFER_SIZE_COUNT);
        queue.last_buffer_size_min.push(3);
        queue.last_buffer_size_max.push(Reverse(3));
        assert!(audio
            .fill_buffer(&mut [0.; USUAL_FRAME_SIZE * CHANNEL_NUM])
            .is_empty());
        assert_eq!(audio.diagnostics.catchup_frames, 18);
        audio.reset(); // Counters must not disappear on channel/lifecycle cleanup.

        for id in [10, 12, 16] {
            audio
                .handle_packet(ClientId(7), packet(id, &encoded[..length]))
                .unwrap();
        }
        audio.handle_packet(ClientId(7), packet(17, &[])).unwrap();
        // Decode 10; FEC for 11; 12; PLC for 13/14; FEC for 15; 16; end.
        for _ in 0..7 {
            assert!(audio
                .fill_buffer(&mut [0.; USUAL_FRAME_SIZE * CHANNEL_NUM])
                .is_empty());
        }
        assert_eq!(
            audio.fill_buffer(&mut [0.; USUAL_FRAME_SIZE * CHANNEL_NUM]),
            vec![ClientId(7)]
        );
        let diagnostics = audio.take_diagnostics();
        assert_eq!(diagnostics.plc_frames, 2 * USUAL_FRAME_SIZE as u64);
        assert_eq!(diagnostics.fec_frames, 2 * USUAL_FRAME_SIZE as u64);
        assert_eq!(diagnostics.catchup_frames, 18);
        assert_eq!(diagnostics.truncated_packets, 0);
        assert_eq!(diagnostics.buffering_frames, 0);
        assert_eq!(diagnostics.decode_errors, 0);
        let cleared = audio.take_diagnostics();
        assert_eq!(cleared.plc_frames, 0);
        assert_eq!(cleared.fec_frames, 0);
        assert_eq!(cleared.catchup_frames, 0);
        assert!(audio.get_queues().is_empty());
    }
}
