//! One bounded DSP worker; one coalescing loader only while a selection is pending.
use crate::audio_models::{Chain, Frame, Selection};
use std::{
    collections::VecDeque,
    path::PathBuf,
    sync::{mpsc, Arc, Mutex},
    thread,
    time::{Duration, Instant},
};
use tokio::sync::mpsc as async_mpsc;
#[derive(Clone)]
struct Input {
    start: u64,
    samples: [i16; 960],
    epoch: u64,
}
enum Work {
    Audio(Input),
    Wake,
}
pub enum Event {
    Applied {
        generation: u64,
        selection: Selection,
        elapsed_ms: u128,
    },
    Failed {
        generation: u64,
        selection: Selection,
        error: String,
    },
    Audio {
        generation: u64,
        epoch: u64,
        frame: Frame,
    },
    Gap {
        epoch: u64,
    },
}
struct Prepared {
    generation: u64,
    epoch: u64,
    chain: Chain,
    cursor: u64,
    frames: VecDeque<Frame>,
    began: Instant,
}
struct Shared {
    desired: Selection,
    generation: u64,
    epoch: u64,
    storage: PathBuf,
    loading: bool,
    stopped: bool,
    recent: VecDeque<Input>,
    prepared: Option<std::result::Result<Prepared, (u64, String)>>,
}
pub struct Processor {
    tx: mpsc::SyncSender<Work>,
    shared: Arc<Mutex<Shared>>,
    pub events: async_mpsc::Receiver<Event>,
    worker: Option<thread::JoinHandle<()>>,
    cursor: u64,
}
impl Processor {
    pub fn new() -> Self {
        let (tx, rx) = mpsc::sync_channel(5);
        let (output, events) = async_mpsc::channel(5);
        let shared = Arc::new(Mutex::new(Shared {
            desired: Selection::default(),
            generation: 0,
            epoch: 0,
            storage: std::env::temp_dir().join("mobilespeak-models"),
            loading: false,
            stopped: false,
            recent: VecDeque::new(),
            prepared: None,
        }));
        let state = shared.clone();
        let worker = thread::Builder::new()
            .name("MobileSpeakDSP".into())
            .spawn(move || {
                let mut chain = Chain::new(Selection::default(), &state.lock().unwrap().storage)
                    .expect("built-in audio processors");
                let mut generation = 0;
                let mut epoch = 0;
                let mut cursor = None;
                let mut last_output = None;
                while let Ok(work) = rx.recv() {
                    if state.lock().unwrap().stopped {
                        break;
                    }
                    let prepared = state.lock().unwrap().prepared.take();
                    if let Some(prepared) = prepared {
                        match prepared {
                            Ok(mut p) => {
                                let s = state.lock().unwrap();
                                if p.generation == s.generation {
                                    let boundary =
                                        last_output.unwrap_or(cursor.unwrap_or(p.cursor));
                                    let current_epoch = s.epoch;
                                    let recent = s.recent.clone();
                                    drop(s);
                                    let mut preparation_error = None;
                                    if p.epoch == current_epoch {
                                        let pending: Vec<_> =
                                            recent.iter().filter(|i| i.start >= p.cursor).collect();
                                        if pending.len() > 4 {
                                            preparation_error = Some(
                                                "Model handoff exceeded 80 ms catch-up bound"
                                                    .to_owned(),
                                            );
                                        } else {
                                            for i in pending {
                                                match p.chain.process(i.start, &i.samples) {
                                                    Ok(frames) => p.frames.extend(frames),
                                                    Err(error) => {
                                                        preparation_error = Some(error);
                                                        break;
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        p.chain.reset();
                                        p.frames.clear();
                                    }
                                    let s = state.lock().unwrap();
                                    if p.generation == s.generation && preparation_error.is_some() {
                                        drop(s);
                                        if output
                                            .blocking_send(Event::Failed {
                                                generation: p.generation,
                                                selection: chain.selection,
                                                error: preparation_error.unwrap(),
                                            })
                                            .is_err()
                                        {
                                            break;
                                        }
                                    } else if s.generation == p.generation {
                                        epoch = s.epoch;
                                        let old = std::mem::replace(&mut chain, p.chain);
                                        drop(s);
                                        drop(old);
                                        if epoch != current_epoch {
                                            chain.reset();
                                            p.frames.clear();
                                            cursor = None;
                                            last_output = None;
                                        }
                                        generation = p.generation;
                                        if output
                                            .blocking_send(Event::Applied {
                                                generation,
                                                selection: chain.selection,
                                                elapsed_ms: p.began.elapsed().as_millis(),
                                            })
                                            .is_err()
                                        {
                                            break;
                                        }
                                        // Warm audio is usable only if it has never been emitted by the old chain.
                                        for frame in
                                            p.frames.into_iter().filter(|f| f.start >= boundary)
                                        {
                                            last_output = Some(frame.start + 960);
                                            if output
                                                .blocking_send(Event::Audio {
                                                    generation,
                                                    epoch,
                                                    frame,
                                                })
                                                .is_err()
                                            {
                                                return;
                                            }
                                        }
                                    }
                                }
                            }
                            Err((g, error)) => {
                                let s = state.lock().unwrap();
                                if g == s.generation {
                                    let selection = chain.selection;
                                    drop(s);
                                    if output
                                        .blocking_send(Event::Failed {
                                            generation: g,
                                            selection,
                                            error,
                                        })
                                        .is_err()
                                    {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    let next_epoch = state.lock().unwrap().epoch;
                    if epoch != next_epoch {
                        chain.reset();
                        epoch = next_epoch;
                        cursor = None;
                        last_output = None;
                    }
                    if let Work::Audio(input) = work {
                        if input.epoch != epoch {
                            continue;
                        }
                        if cursor.is_some_and(|c| c != input.start) {
                            chain.reset();
                            last_output = None;
                            state.lock().unwrap().recent.clear();
                            if output.blocking_send(Event::Gap { epoch }).is_err() {
                                break;
                            }
                        }
                        cursor = Some(input.start + 960);
                        {
                            let mut s = state.lock().unwrap();
                            if s.recent.len() == 16 {
                                s.recent.pop_front();
                            }
                            s.recent.push_back(input.clone());
                        }
                        let result = chain.process(input.start, &input.samples);
                        match result {
                            Ok(frames) => {
                                for frame in frames {
                                    if last_output.is_some_and(|c| frame.start < c) {
                                        continue;
                                    }
                                    last_output = Some(frame.start + 960);
                                    if output
                                        .blocking_send(Event::Audio {
                                            generation,
                                            epoch,
                                            frame,
                                        })
                                        .is_err()
                                    {
                                        return;
                                    }
                                }
                            }
                            Err(error) => {
                                chain.reset();
                                last_output = None;
                                if output.blocking_send(Event::Gap { epoch }).is_err() {
                                    break;
                                }
                                if output
                                    .blocking_send(Event::Failed {
                                        generation,
                                        selection: chain.selection,
                                        error,
                                    })
                                    .is_err()
                                {
                                    break;
                                }
                            }
                        }
                    }
                }
            })
            .expect("audio worker creation");
        Self {
            tx,
            shared,
            events,
            worker: Some(worker),
            cursor: 0,
        }
    }
    pub fn configure(&self, storage: PathBuf) {
        self.shared.lock().unwrap().storage = storage;
    }
    pub fn request(&self, selection: Selection) -> u64 {
        let mut s = self.shared.lock().unwrap();
        s.generation += 1;
        s.desired = selection;
        let generation = s.generation;
        if s.loading {
            return generation;
        }
        s.loading = true;
        drop(s);
        let state = self.shared.clone();
        let wake = self.tx.clone();
        let spawned = thread::Builder::new()
            .name("MobileSpeakModelLoader".into())
            .spawn(move || loop {
                let (selection, generation, storage) = {
                    let s = state.lock().unwrap();
                    if s.stopped {
                        return;
                    }
                    (s.desired, s.generation, s.storage.clone())
                };
                let began = Instant::now();
                let prepared = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                    let mut chain = Chain::new(selection, &storage)?;
                    let (epoch, recent) = {
                        let s = state.lock().unwrap();
                        // Warming the whole 320 ms history can itself fall behind capture
                        // on a phone. Two frames exercise the model without a long replay.
                        (
                            s.epoch,
                            s.recent
                                .iter()
                                .skip(s.recent.len().saturating_sub(2))
                                .cloned()
                                .collect::<Vec<_>>(),
                        )
                    };
                    let mut cursor = recent.first().map_or(0, |i| i.start);
                    let mut frames = VecDeque::new();
                    // Warm at most 40ms, then catch up bounded snapshots. No warmup is sent twice.
                    for input in recent {
                        frames.extend(chain.process(input.start, &input.samples)?);
                        cursor = input.start + 960;
                        while frames.len() > 5 {
                            frames.pop_front();
                        }
                    }
                    for _ in 0..8 {
                        let (current_epoch, latest) = {
                            let s = state.lock().unwrap();
                            if s.stopped || s.generation != generation {
                                return Err("superseded".into());
                            }
                            (s.epoch, s.recent.clone())
                        };
                        if current_epoch != epoch {
                            chain.reset();
                            frames.clear();
                            break;
                        }
                        let pending: Vec<_> =
                            latest.into_iter().filter(|i| i.start >= cursor).collect();
                        if pending.is_empty() {
                            break;
                        }
                        if pending[0].start != cursor {
                            return Err("Model preparation could not keep up with capture".into());
                        }
                        for input in pending {
                            frames.extend(chain.process(input.start, &input.samples)?);
                            cursor = input.start + 960;
                            while frames.len() > 5 {
                                frames.pop_front();
                            }
                        }
                        if began.elapsed() > Duration::from_secs(5) {
                            return Err("Model preparation timed out".into());
                        }
                    }
                    Ok(Prepared {
                        generation,
                        epoch,
                        chain,
                        cursor,
                        frames,
                        began,
                    })
                }))
                .unwrap_or_else(|_| Err("Audio model initialization panicked".into()));
                let mut s = state.lock().unwrap();
                if s.stopped {
                    return;
                }
                if s.generation != generation {
                    continue;
                }
                let old = s.prepared.replace(prepared.map_err(|e| (generation, e)));
                s.loading = false;
                drop(s);
                drop(old);
                let _ = wake.try_send(Work::Wake);
                return;
            });
        if let Err(error) = spawned {
            let mut s = self.shared.lock().unwrap();
            s.loading = false;
            s.prepared = Some(Err((
                s.generation,
                format!("Cannot start model loader: {error}"),
            )));
            drop(s);
            let _ = self.tx.try_send(Work::Wake);
        }
        generation
    }
    pub fn epoch(&self) -> u64 {
        self.shared.lock().unwrap().epoch
    }
    pub fn invalidate(&mut self) {
        let mut s = self.shared.lock().unwrap();
        s.epoch += 1;
        s.recent.clear();
        drop(s);
        let _ = self.tx.try_send(Work::Wake);
    }
    pub fn capture(&mut self, samples: &[i16]) {
        let Ok(samples) = samples.try_into() else {
            return;
        };
        let input = Input {
            start: self.cursor,
            samples,
            epoch: self.epoch(),
        };
        self.cursor += 960;
        let _ = self.tx.try_send(Work::Audio(input));
    }
}
impl Drop for Processor {
    fn drop(&mut self) {
        self.shared.lock().unwrap().stopped = true;
        self.events.close();
        let _ = self.tx.try_send(Work::Wake);
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::NoiseSuppressionMode as Noise;
    #[tokio::test]
    async fn live_switches_latest_selection_failure_and_epoch_cancellation() {
        let mut p = Processor::new();
        let storage =
            std::env::temp_dir().join(format!("mobilespeak-switch-test-{}", std::process::id()));
        p.configure(storage.clone());
        let modes = [Noise::Dpdfnet2, Noise::Rnnoise, Noise::None];
        let mut active = 0;
        let mut last = None;
        let mut frames = 0;
        let mut maximum = Duration::ZERO;
        let mut selections = Vec::new();
        selections.extend(modes.into_iter().map(|noise| Selection { noise }));
        // Every ordered noise pair with the fixed Earshot detector.
        for from in modes {
            for to in modes {
                if from != to {
                    selections.extend([Selection { noise: from }, Selection { noise: to }]);
                }
            }
        }
        let speech: Vec<i16> = include_bytes!("../tests/fixtures/speech.pcm")
            .chunks_exact(2)
            .map(|b| i16::from_le_bytes([b[0], b[1]]))
            .collect();
        let mut capture_index = 0;
        for selection in selections {
            let noise = selection.noise;
            let selection = Selection { noise };
            let generation = p.request(selection);
            let began = Instant::now();
            let mut applied = false;
            while !applied || began.elapsed() < Duration::from_millis(250) {
                assert!(
                    began.elapsed() < Duration::from_secs(5),
                    "switch did not apply {selection:?}"
                );
                let offset = (capture_index * 960) % (speech.len() / 960 * 960);
                let samples: [i16; 960] = if capture_index % 25 < 3 {
                    [0; 960]
                } else {
                    speech[offset..offset + 960].try_into().unwrap()
                };
                capture_index += 1;
                p.capture(&samples);
                let wait = tokio::time::sleep(Duration::from_millis(20));
                tokio::pin!(wait);
                loop {
                    tokio::select! {
                        _=&mut wait=>break,
                        event=p.events.recv()=>match event.unwrap(){
                            Event::Applied{generation:g,selection:s,..}=>{assert_eq!(g,generation);assert_eq!(s,selection);active=g;applied=true;maximum=maximum.max(began.elapsed());},
                            Event::Audio{generation:g,epoch,frame}=>{assert_eq!(g,active);assert_eq!(epoch,p.epoch());if let Some(end)=last{assert_eq!(frame.start,end,"lost or repeated audio at switch");}last=Some(frame.start+960);frames+=1;},
                            Event::Failed{error,..}=>panic!("switch failed {error}"),Event::Gap {..}=>panic!("queue dropped audio"),
                        }
                    }
                }
            }
        }
        assert!(frames > 100);
        // Continuous setting changes are coalesced; stale loader completion cannot win.
        let _ = p.request(Selection {
            noise: Noise::Dpdfnet2,
        });
        let _ = p.request(Selection {
            noise: Noise::Dpdfnet2,
        });
        let wanted = Selection { noise: Noise::None };
        let latest = p.request(wanted);
        loop {
            match tokio::time::timeout(Duration::from_secs(5), p.events.recv())
                .await
                .unwrap()
                .unwrap()
            {
                Event::Applied {
                    generation,
                    selection,
                    ..
                } => {
                    assert_eq!(generation, latest);
                    assert_eq!(selection, wanted);
                    break;
                }
                Event::Failed { error, .. } => panic!("latest switch failed {error}"),
                _ => {}
            }
        }
        // Bad storage is a real initialization error, with old None+Earshot retained.
        p.configure(PathBuf::from("/dev/null/models"));
        let failed = p.request(Selection {
            noise: Noise::Dpdfnet2,
        });
        loop {
            match tokio::time::timeout(Duration::from_secs(5), p.events.recv())
                .await
                .unwrap()
                .unwrap()
            {
                Event::Failed {
                    generation,
                    selection,
                    ..
                } => {
                    assert_eq!(generation, failed);
                    assert_eq!(selection, wanted);
                    break;
                }
                Event::Applied { .. } => panic!("failed initialization changed active selection"),
                _ => {}
            }
        }
        p.configure(storage.clone());
        let _ = p.request(Selection {
            noise: Noise::Dpdfnet2,
        });
        p.invalidate();
        let epoch = p.epoch();
        loop {
            match tokio::time::timeout(Duration::from_secs(5), p.events.recv())
                .await
                .unwrap()
                .unwrap()
            {
                Event::Applied { .. } => break,
                Event::Audio { epoch: e, .. } => {
                    assert_ne!(e, epoch, "old audio escaped invalidation")
                }
                _ => {}
            }
        }
        eprintln!(
            "15 live switches covering all directed noise pairs with fixed Earshot, {frames} unique contiguous frames, max response {:?}",
            maximum
        );
        drop(p);
        let _ = std::fs::remove_dir_all(storage);
    }
}
