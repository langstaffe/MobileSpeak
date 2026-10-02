//! Single session producer, one system render consumer. No UI locks on this path.
use std::{
    io,
    os::unix::net::UnixDatagram,
    sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering::*},
};

// Stereo frames at 48 kHz. Tuning knobs: start at 40 ms; grow to two
// render batches, with a fixed ceiling (larger callbacks receive a silent tail).
pub const FRAME: usize = 960;
const PREPARED: usize = FRAME * 2;
const CAPACITY: usize = 16384;
// A partial read can require one whole FRAME. Keep that headroom after rounding
// the target to FRAME units, including when reserve growth adds silent frames.
const MAX_TARGET: usize = (CAPACITY / FRAME - 1) * FRAME;

pub struct Playback {
    samples: Box<[AtomicU64]>, // one atomic stereo pair; reset never races non-atomic sample access
    read: AtomicUsize,
    write: AtomicUsize,
    floor: AtomicUsize,
    target: AtomicUsize,
    reserve: AtomicUsize,
    rendering: AtomicBool,
    pub active: AtomicBool,
    pub revision: AtomicU64,
    pub renders: AtomicU64,
    underruns: AtomicU64,
    missing_frames: AtomicU64,
    growth_frames: AtomicU64,
    resets: AtomicU64,
    max_callback: AtomicUsize,
    ready_revision: AtomicU64,
    pending: AtomicBool,
    signal: UnixDatagram,
}
impl Playback {
    pub fn new() -> io::Result<(Self, UnixDatagram)> {
        let (signal, receiver) = UnixDatagram::pair()?;
        signal.set_nonblocking(true)?;
        receiver.set_nonblocking(true)?;
        Ok((
            Self {
                samples: (0..CAPACITY).map(|_| AtomicU64::new(0)).collect(),
                read: AtomicUsize::new(0),
                write: AtomicUsize::new(0),
                floor: AtomicUsize::new(0),
                target: AtomicUsize::new(PREPARED),
                reserve: AtomicUsize::new(PREPARED),
                rendering: AtomicBool::new(false),
                active: AtomicBool::new(false),
                revision: AtomicU64::new(0),
                renders: AtomicU64::new(0),
                underruns: AtomicU64::new(0),
                missing_frames: AtomicU64::new(0),
                growth_frames: AtomicU64::new(0),
                resets: AtomicU64::new(0),
                max_callback: AtomicUsize::new(0),
                ready_revision: AtomicU64::new(u64::MAX),
                pending: AtomicBool::new(false),
                signal,
            },
            receiver,
        ))
    }
    // Ordinary session thread only. Counters are cumulative and deliberately
    // independent of PCM resets, so pauses/channel changes remain diagnosable.
    pub fn diagnostics(&self) -> [u64; 5] {
        [
            self.renders.load(Relaxed),
            self.underruns.load(Relaxed),
            self.missing_frames.load(Relaxed),
            self.growth_frames.load(Relaxed),
            self.resets.load(Relaxed),
        ]
    }
    pub fn max_callback(&self) -> usize {
        self.max_callback.load(Relaxed)
    }

    pub fn request(&self) {
        if !self.pending.swap(true, AcqRel) && self.signal.send(&[1]).is_err() {
            // EINTR/full socket: a queued signal or the next callback retries; never wait.
            self.pending.store(false, Release);
        }
    }
    pub fn prepared(&self, revision: u64) {
        self.ready_revision.store(revision, Release);
    }
    pub fn acknowledged(&self) {
        self.pending.store(false, Release);
    }
    // Called only on an ordinary platform lifecycle thread.
    pub fn set_active(&self, active: bool) {
        if self.active.swap(active, AcqRel) != active {
            self.revision.fetch_add(1, AcqRel);
            self.request();
        }
    }
    // Producer only. The consumer owns read; discard via a separately published floor.
    pub fn clear(&self) {
        self.resets.fetch_add(1, Relaxed);
        self.floor.store(self.write.load(Acquire), Release);
        self.target.store(PREPARED, Release);
        self.reserve.store(PREPARED, Relaxed);
    }
    pub fn available(&self) -> usize {
        self.write
            .load(Acquire)
            .saturating_sub(self.read.load(Acquire).max(self.floor.load(Acquire)))
    }
    #[cfg(test)]
    pub fn needed(&self) -> usize {
        self.target
            .load(Acquire)
            .saturating_sub(self.available())
            .div_ceil(FRAME)
    }
    // Session thread only. Growing lookahead must add delay, not advance the
    // network decoder into packets that have not arrived yet. Keep tsclientlib's
    // own jitter/loss handling unchanged for actual consumed audio.
    pub fn refill(
        &self,
        audio: &mut crate::tsclientlib_audio::AudioHandler,
    ) -> Vec<tsclientlib::ClientId> {
        let target = self.target.load(Acquire).div_ceil(FRAME) * FRAME;
        let previous = self.reserve.swap(target, Relaxed);
        self.growth_frames
            .fetch_add(target.saturating_sub(previous) as u64, Relaxed);
        for _ in 0..target.saturating_sub(previous) / FRAME {
            self.push(&[0.; FRAME * 2]);
        }
        let mut ended = Vec::new();
        // Snapshot the budget; concurrent callbacks cannot extend this loop.
        for _ in 0..target.saturating_sub(self.available()).div_ceil(FRAME) {
            let mut samples = [0.; FRAME * 2];
            ended.extend(audio.fill_buffer(&mut samples));
            self.push(&samples);
        }
        ended
    }

    pub fn push(&self, samples: &[f32; FRAME * 2]) {
        let write = self.write.load(Relaxed);
        assert!(self.available() + FRAME <= CAPACITY);
        for (i, pair) in samples.chunks_exact(2).enumerate() {
            let bits = u64::from(pair[0].clamp(-1., 1.).to_bits())
                | (u64::from(pair[1].clamp(-1., 1.).to_bits()) << 32);
            self.samples[(write + i) % CAPACITY].store(bits, Relaxed);
        }
        self.write.store(write + FRAME, Release);
    }
    // Pointers are preallocated platform buffers, frames are stereo pairs. A null right
    // pointer requests mono. Stride 1 is planar/mono; stride 2 is interleaved stereo.
    pub unsafe fn render(
        &self,
        left: *mut f32,
        right: *mut f32,
        frames: usize,
        stride: usize,
    ) -> usize {
        self.renders.fetch_add(1, Relaxed);
        self.max_callback.fetch_max(frames, Relaxed);
        let owns = self
            .rendering
            .compare_exchange(false, true, Acquire, Relaxed)
            .is_ok();
        let revision = self.revision.load(Acquire);
        let read = self.read.load(Relaxed).max(self.floor.load(Acquire));
        let ready =
            owns && self.active.load(Acquire) && self.ready_revision.load(Acquire) == revision;
        let count = if ready {
            frames.min(self.write.load(Acquire).saturating_sub(read))
        } else {
            0
        };
        if ready && count < frames {
            self.underruns.fetch_add(1, Relaxed);
            self.missing_frames
                .fetch_add((frames - count) as u64, Relaxed);
        }
        for i in 0..frames {
            let bits = if i < count {
                self.samples[(read + i) % CAPACITY].load(Relaxed)
            } else {
                0
            };
            let l = f32::from_bits(bits as u32);
            let r = f32::from_bits((bits >> 32) as u32);
            *left.add(i * stride) = if right.is_null() { (l + r) * 0.5 } else { l };
            if !right.is_null() {
                *right.add(i * stride) = r;
            }
        }
        // A concurrent pause/reset must not publish an old channel's samples.
        if revision != self.revision.load(Acquire) || read < self.floor.load(Acquire) {
            for i in 0..frames {
                *left.add(i * stride) = 0.;
                if !right.is_null() {
                    *right.add(i * stride) = 0.;
                }
            }
        }
        if owns {
            self.read.store(read + count, Release);
            self.rendering.store(false, Release);
        }
        self.target.fetch_max(
            frames.saturating_mul(2).clamp(PREPARED, MAX_TARGET),
            Relaxed,
        );
        // Includes empty/paused renders. Acknowledge-before-fill makes a concurrent
        // consumption either part of this fill or a new queued wakeup.
        self.request();
        count
    }
}

// Diagnostic state only: one entry per active receive queue, owned by the session
// thread. Sequence skips are not loss counts: reordered packets may fill them later.
pub(crate) struct ReceiveTrace {
    pub started: std::time::Instant,
    pub last_packet: std::time::Instant,
    pub packets: u64,
    pub end_markers: u64,
    pub sequence_skips: u64,
    pub reordered_or_duplicate: u64,
    pub max_gap_ms: u128,
    pub rejected: u64,
    highest_sequence: Option<u16>,
}
impl ReceiveTrace {
    pub fn new(now: std::time::Instant) -> Self {
        Self {
            started: now,
            last_packet: now,
            packets: 0,
            end_markers: 0,
            sequence_skips: 0,
            reordered_or_duplicate: 0,
            max_gap_ms: 0,
            rejected: 0,
            highest_sequence: None,
        }
    }
    pub fn received(&mut self, sequence: u16, end_marker: bool, now: std::time::Instant) {
        self.max_gap_ms = self
            .max_gap_ms
            .max(now.saturating_duration_since(self.last_packet).as_millis());
        self.last_packet = now;
        self.packets += 1;
        self.end_markers += u64::from(end_marker);
        if let Some(highest) = self.highest_sequence {
            let advance = sequence.wrapping_sub(highest);
            if advance == 0 || advance >= 32768 {
                self.reordered_or_duplicate += 1;
                return;
            }
            self.sequence_skips += u64::from(advance - 1);
        }
        self.highest_sequence = Some(sequence);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn arriving_music_packet_is_not_deleted_by_previous_loss_count() {
        use crate::tsclientlib_audio::AudioHandler;
        use audiopus::{coder::Encoder, Application, Channels, SampleRate};
        use tsclientlib::ClientId;
        use tsproto_packets::packets::{AudioData, CodecType, Direction, InAudioBuf, OutAudio};
        let encoder =
            Encoder::new(SampleRate::Hz48000, Channels::Stereo, Application::Audio).unwrap();
        let mut encoded = [0; 1275];
        let length = encoder.encode(&[100; FRAME * 2], &mut encoded).unwrap();
        let packet = |sequence, data: &[u8]| {
            InAudioBuf::try_new(
                Direction::S2C,
                OutAudio::new(&AudioData::S2C {
                    id: sequence,
                    from: 7,
                    codec: CodecType::OpusMusic,
                    data,
                })
                .into_vec(),
            )
            .unwrap()
        };
        let mut audio = AudioHandler::new();
        audio
            .handle_packet(ClientId(7), packet(10, &encoded[..length]))
            .unwrap();
        // Decode real audio, then three missing frames. A delayed burst arrives
        // before the next fill, exactly as receive_end last_packet_age_ms=0.
        for _ in 0..4 {
            assert!(audio.fill_buffer(&mut [0.; FRAME * 2]).is_empty());
        }
        audio
            .handle_packet(ClientId(7), packet(11, &encoded[..length]))
            .unwrap();
        assert!(
            audio.fill_buffer(&mut [0.; FRAME * 2]).is_empty(),
            "fresh queued music was discarded by stale loss count"
        );
        assert!(audio.get_queues().contains_key(&ClientId(7)));
        audio.handle_packet(ClientId(7), packet(12, &[])).unwrap();
        assert_eq!(audio.fill_buffer(&mut [0.; FRAME * 2]), vec![ClientId(7)]);
        assert!(audio.fill_buffer(&mut [0.; FRAME * 2]).is_empty());
    }

    #[test]
    fn refill_reports_each_ended_client_once_for_marker_and_packet_starvation() {
        use crate::tsclientlib_audio::AudioHandler;
        use audiopus::{coder::Encoder, Application, Channels, SampleRate};
        use tsclientlib::ClientId;
        use tsproto_packets::packets::{AudioData, CodecType, Direction, InAudioBuf, OutAudio};
        let (p, _) = Playback::new().unwrap();
        p.set_active(true);
        p.prepared(p.revision.load(Acquire));
        let encoder =
            Encoder::new(SampleRate::Hz48000, Channels::Stereo, Application::Audio).unwrap();
        let mut encoded = [0; 1275];
        let length = encoder.encode(&[100; FRAME * 2], &mut encoded).unwrap();
        let mut audio = AudioHandler::new();
        for (client, sequence, data) in [
            (7, 0, &encoded[..length]),
            (8, 0, &encoded[..length]),
            (7, 1, &[][..]),
        ] {
            let packet = OutAudio::new(&AudioData::S2C {
                id: sequence,
                from: client,
                codec: CodecType::OpusMusic,
                data,
            });
            audio
                .handle_packet(
                    ClientId(client),
                    InAudioBuf::try_new(Direction::S2C, packet.into_vec()).unwrap(),
                )
                .unwrap();
        }
        let mut stopped = Vec::new();
        let mut output = [0.; FRAME * 2];
        // The second receive queue may first wait out tsclientlib's startup
        // jitter buffering (up to 0.5 s), before its packet-loss cutoff.
        for _ in 0..40 {
            stopped.extend(p.refill(&mut audio));
            unsafe {
                p.render(output.as_mut_ptr(), output.as_mut_ptr().add(1), FRAME, 2);
            }
        }
        assert_eq!(stopped.iter().filter(|&&id| id == ClientId(7)).count(), 1);
        assert_eq!(stopped.iter().filter(|&&id| id == ClientId(8)).count(), 1);
        assert_eq!(stopped.len(), 2);
        assert!(audio.get_queues().is_empty());
    }

    #[test]
    fn receive_trace_handles_wrap_reordering_end_markers_and_new_stream() {
        use std::time::{Duration, Instant};
        let now = Instant::now();
        let mut trace = ReceiveTrace::new(now);
        for (id, ms, end) in [
            (65535, 0, false),
            (0, 20, false),
            (2, 90, false),
            (1, 95, false),
            (2, 96, false),
            (3, 110, true),
        ] {
            trace.received(id, end, now + Duration::from_millis(ms));
        }
        assert_eq!(trace.packets, 6);
        assert_eq!(trace.sequence_skips, 1); // arrival of 1 doesn't turn this into proven loss
        assert_eq!(trace.reordered_or_duplicate, 2);
        assert_eq!(trace.max_gap_ms, 70);
        assert_eq!(trace.end_markers, 1);
        assert_eq!(ReceiveTrace::new(now).end_markers, 0);
    }

    #[test]
    fn diagnostics_distinguish_starvation_growth_and_lifecycle_silence() {
        let (p, _) = Playback::new().unwrap();
        let mut audio = crate::tsclientlib_audio::AudioHandler::new();
        let mut out = [0.; 2048];
        p.set_active(true);
        unsafe {
            p.render(out.as_mut_ptr(), std::ptr::null_mut(), 2048, 1);
        }
        assert_eq!(p.diagnostics(), [1, 0, 0, 0, 0]); // waiting for initial preparation
        p.refill(&mut audio);
        p.prepared(p.revision.load(Acquire));
        let growth = p.diagnostics()[3];
        assert_eq!(growth, 3 * FRAME as u64);
        for _ in 0..3 {
            unsafe {
                p.render(out.as_mut_ptr(), std::ptr::null_mut(), 2048, 1);
            }
        }
        assert_eq!(p.diagnostics(), [4, 1, 1344, growth, 0]);
        assert_eq!(p.max_callback(), 2048);
        p.set_active(false);
        p.clear();
        unsafe {
            p.render(out.as_mut_ptr(), std::ptr::null_mut(), 2048, 1);
        }
        assert_eq!(p.diagnostics(), [5, 1, 1344, growth, 1]); // pause is not an underrun
    }

    #[test]
    fn larger_render_batch_does_not_expire_a_live_music_queue() {
        use crate::tsclientlib_audio::AudioHandler;
        use audiopus::{coder::Encoder, Application, Channels, SampleRate};
        use tsclientlib::ClientId;
        use tsproto_packets::packets::{AudioData, CodecType, Direction, InAudioBuf, OutAudio};
        let (p, _) = Playback::new().unwrap();
        p.set_active(true);
        let mut audio = AudioHandler::new();
        // Normal prefill before the bot's first packet.
        for _ in 0..p.needed() {
            p.push(&[0.; FRAME * 2]);
        }
        p.prepared(p.revision.load(Acquire));
        let encoder =
            Encoder::new(SampleRate::Hz48000, Channels::Stereo, Application::Audio).unwrap();
        let mut encoded = [0u8; 1275];
        let len = encoder.encode(&[100i16; FRAME * 2], &mut encoded).unwrap();
        let packet = OutAudio::new(&AudioData::S2C {
            id: 0,
            from: 7,
            codec: CodecType::OpusMusic,
            data: &encoded[..len],
        });
        audio
            .handle_packet(
                ClientId(7),
                InAudioBuf::try_new(Direction::S2C, packet.into_vec()).unwrap(),
            )
            .unwrap();
        let mut output = [0.; 4096];
        unsafe {
            p.render(output.as_mut_ptr(), output.as_mut_ptr().add(1), 2048, 2);
        }
        assert!(
            p.refill(&mut audio).is_empty(),
            "reserve expansion falsely expired live music"
        );
        assert!(audio.get_queues().contains_key(&ClientId(7)));

        // Continuous music, changing render batches, no network loss. The queue
        // must remain alive after expansion rather than repeatedly restarting.
        let mut sequence = 1;
        let mut arrived = 0;
        let mut output = [0.; 8192];
        for batch in [512, 1024, 2048, 4096, 192].into_iter().cycle().take(100) {
            arrived += batch;
            while arrived >= FRAME {
                arrived -= FRAME;
                let packet = OutAudio::new(&AudioData::S2C {
                    id: sequence,
                    from: 7,
                    codec: CodecType::OpusMusic,
                    data: &encoded[..len],
                });
                sequence += 1;
                audio
                    .handle_packet(
                        ClientId(7),
                        InAudioBuf::try_new(Direction::S2C, packet.into_vec()).unwrap(),
                    )
                    .unwrap();
            }
            unsafe {
                p.render(output.as_mut_ptr(), output.as_mut_ptr().add(1), batch, 2);
            }
            assert!(
                p.refill(&mut audio).is_empty(),
                "continuous bot was falsely stopped"
            );
            assert!(p.available() <= CAPACITY);
        }
    }

    #[test]
    fn large_callback_followed_by_partial_reads_never_overfills_pcm() {
        let (p, _) = Playback::new().unwrap();
        let mut audio = crate::tsclientlib_audio::AudioHandler::new();
        let mut output = [0.; CAPACITY];
        p.set_active(true);
        p.refill(&mut audio);
        p.prepared(p.revision.load(Acquire));
        // Reach the reserve ceiling, then require a whole 20 ms refill for a
        // one-frame consumption. Rounding must still fit in the fixed ring.
        for frames in [8192, 1, 127, CAPACITY, 37, 960, 4097, 1] {
            unsafe { p.render(output.as_mut_ptr(), std::ptr::null_mut(), frames, 1) };
            assert!(p.refill(&mut audio).is_empty());
            assert!(p.available() <= CAPACITY);
        }
    }

    #[test]
    fn partial_wrap_empty_reset_and_coalesced_wakeup() {
        let (p, socket) = Playback::new().unwrap();
        p.set_active(true);
        p.prepared(p.revision.load(Acquire));
        let mut signal = [0];
        assert_eq!(socket.recv(&mut signal).unwrap(), 1);
        p.acknowledged();
        let mut frame = [0.; FRAME * 2];
        for pair in frame.chunks_exact_mut(2) {
            pair.copy_from_slice(&[0.25, 0.75]);
        }
        for _ in 0..40 {
            p.push(&frame);
            for size in [127, 833, 64] {
                let mut out = vec![9.; size * 2];
                let n = unsafe { p.render(out.as_mut_ptr(), out.as_mut_ptr().add(1), size, 2) };
                assert_eq!(n, if size == 64 { 0 } else { size });
                assert!(out[..n * 2]
                    .chunks_exact(2)
                    .all(|pair| pair == [0.25, 0.75]));
                assert!(out[n * 2..].iter().all(|&v| v == 0.));
            }
        }
        assert_eq!(socket.recv(&mut signal).unwrap(), 1);
        assert_eq!(
            socket.recv(&mut signal).unwrap_err().kind(),
            io::ErrorKind::WouldBlock
        );
        p.acknowledged();
        p.push(&frame);
        p.clear();
        let mut out = [9.; 4096];
        unsafe {
            assert_eq!(
                p.render(out.as_mut_ptr(), std::ptr::null_mut(), out.len(), 1),
                0
            );
        }
        assert!(out.iter().all(|&v| v == 0.));
        assert!(p.needed() > 2);
        assert_eq!(socket.recv(&mut signal).unwrap(), 1); // empty still wakes
        p.acknowledged();
        for _ in 0..p.needed() {
            p.push(&frame);
        }
        assert!(p.available() <= CAPACITY);
        unsafe {
            p.render(out.as_mut_ptr(), std::ptr::null_mut(), out.len(), 1);
        }
        assert!(out.iter().all(|&v| v == 0.5));
    }
    #[test]
    fn resume_requires_current_prefill_and_planar_render_keeps_boundaries() {
        let (p, _) = Playback::new().unwrap();
        p.set_active(true);
        let old = p.revision.load(Acquire);
        p.push(&[0.75; FRAME * 2]);
        p.prepared(old);
        p.set_active(false);
        p.set_active(true);
        let mut left = [7.; 67];
        let mut right = [7.; 67];
        unsafe {
            assert_eq!(
                p.render(left.as_mut_ptr().add(1), right.as_mut_ptr().add(1), 65, 1),
                0
            );
        }
        p.clear();
        p.push(&[0.25; FRAME * 2]);
        p.prepared(old); // an in-flight refill from before pause cannot enable playback
        unsafe {
            assert_eq!(
                p.render(left.as_mut_ptr().add(1), right.as_mut_ptr().add(1), 65, 1),
                0
            );
        }
        p.prepared(p.revision.load(Acquire));
        unsafe {
            assert_eq!(
                p.render(left.as_mut_ptr().add(1), right.as_mut_ptr().add(1), 65, 1),
                65
            );
        }
        for channel in [left, right] {
            assert_eq!(channel[0], 7.);
            assert_eq!(channel[66], 7.);
            assert!(channel[1..66].iter().all(|&v| v == 0.25));
        }
    }

    #[test]
    fn concurrent_consumer_and_refill_do_not_lose_wakes_or_reorder_samples() {
        use std::{sync::Arc, time::Duration};
        let (p, socket) = Playback::new().unwrap();
        let p = Arc::new(p);
        socket.set_nonblocking(false).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(2)))
            .unwrap();
        let producer = p.clone();
        let worker = std::thread::spawn(move || {
            let mut next = 1u32;
            while socket.recv(&mut [0]).is_ok() {
                producer.acknowledged();
                if !producer.active.load(Acquire) {
                    return;
                }
                let revision = producer.revision.load(Acquire);
                for _ in 0..producer.needed() {
                    let mut frame = [0.; FRAME * 2];
                    for pair in frame.chunks_exact_mut(2) {
                        let value = next as f32 / 1_000_000.;
                        pair.copy_from_slice(&[value, -value]);
                        next += 1;
                    }
                    producer.push(&frame);
                }
                producer.prepared(revision);
            }
            panic!("refill wake was lost");
        });
        p.set_active(true); // prefill without any callback
        let until = std::time::Instant::now() + Duration::from_secs(5);
        let mut consumed = 0u32;
        let mut output = [0.; 254];
        while consumed < 100_000 {
            assert!(
                std::time::Instant::now() < until,
                "empty render did not wake producer"
            );
            let n = unsafe { p.render(output.as_mut_ptr(), output.as_mut_ptr().add(1), 127, 2) };
            for pair in output[..n * 2].chunks_exact(2) {
                consumed += 1;
                assert_eq!(
                    pair,
                    [
                        consumed as f32 / 1_000_000.,
                        -(consumed as f32) / 1_000_000.
                    ]
                );
            }
            std::thread::yield_now();
        }
        p.set_active(false);
        worker.join().unwrap();
    }
}
