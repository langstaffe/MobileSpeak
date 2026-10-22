//! Local receive controls. Analysis sees decoded PCM before any playback gain.
use crate::{report_code, Output, PersistJob};
use nnnoiseless::DenoiseState;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        atomic::{AtomicU64, Ordering},
        mpsc, Arc, Mutex,
    },
    time::{Duration, Instant},
};
use tsclientlib::{ClientId, Connection};

const TARGET_DB: f32 = -22.0;
const MAX_DB: f32 = 18.0;
const MIN_DB: f32 = -24.0;
pub(crate) const MAX_VOLUME_PERCENT: u16 = 300;
static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);
fn token() -> String {
    NEXT_TOKEN.fetch_add(1, Ordering::Relaxed).to_string()
}
fn enabled() -> bool {
    true
}

#[derive(Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Preferences {
    pub agc: bool,
    pub speech_mode: crate::speech::Mode,
    users: HashMap<String, HashMap<String, u16>>,
    #[serde(skip, default = "enabled")]
    writable: bool,
    #[serde(skip)]
    dirty: Option<Instant>,
    #[serde(skip)]
    last_change: Option<Instant>,
}
impl Default for Preferences {
    fn default() -> Self {
        Self {
            agc: enabled(),
            speech_mode: crate::speech::Mode::default(),
            users: HashMap::new(),
            writable: true,
            dirty: None,
            last_change: None,
        }
    }
}
impl Preferences {
    pub fn load(&mut self, root: &Path, out: &Arc<Mutex<Output>>) {
        let result = std::fs::read(root.join("receive-audio.json"));
        let loaded = match result {
            Ok(data) => serde_json::from_slice::<Self>(&data).map_err(|e| e.to_string()),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Self::default()),
            Err(e) => Err(e.to_string()),
        };
        match loaded {
            Ok(value)
                if value.users.iter().all(|(s, users)| {
                    valid_identity(s) && users.iter().all(|(u, v)| valid_identity(u) && *v <= MAX_VOLUME_PERCENT)
                }) =>
            {
                *self = value
            }
            Ok(_) => {
                *self = Self::default();
                self.writable = false;
                report_code(
                    out,
                    "receive_audio_read_failed",
                    Some("Invalid saved volume".into()),
                );
            }
            Err(e) => {
                *self = Self::default();
                self.writable = false;
                report_code(out, "receive_audio_read_failed", Some(e));
            }
        }
        self.publish(out);
    }
    pub fn publish(&self, out: &Arc<Mutex<Output>>) {
        let mut out = out.lock().unwrap();
        out.receive_audio = json!({"agc":self.agc});
        out.notify();
    }
    fn changed(&mut self) {
        let now = Instant::now();
        self.dirty.get_or_insert(now);
        self.last_change = Some(now);
    }
    pub fn set_agc(&mut self, value: bool, out: &Arc<Mutex<Output>>) {
        if !self.writable {
            report_code(out, "receive_audio_read_failed", None);
            return;
        }
        self.agc = value;
        self.changed();
        self.publish(out);
    }
    pub fn set_speech_mode(&mut self, mode: crate::speech::Mode, out: &Arc<Mutex<Output>>) -> bool {
        if !self.writable {
            report_code(out, "receive_audio_read_failed", None);
            return false;
        }
        if self.speech_mode != mode { self.speech_mode = mode; self.changed(); }
        true
    }
    fn volume(&self, server: &str, uid: Option<&str>) -> u16 {
        uid.and_then(|uid| self.users.get(server)?.get(uid))
            .copied()
            .unwrap_or(100)
    }
    fn save_volume(&mut self, server: &str, uid: &str, value: u16) {
        self.users
            .entry(server.into())
            .or_default()
            .insert(uid.into(), value);
        self.changed();
    }
    pub fn flush(
        &mut self,
        root: Option<&Path>,
        tx: &mpsc::Sender<PersistJob>,
        out: &Arc<Mutex<Output>>,
        force: bool,
    ) {
        let Some(first) = self.dirty else {
            return;
        };
        if !force
            && first.elapsed() < Duration::from_secs(1)
            && self
                .last_change
                .is_some_and(|t| t.elapsed() < Duration::from_millis(300))
        {
            return;
        }
        let result = root
            .ok_or_else(|| "Storage is unavailable".to_owned())
            .and_then(|root| {
                let data = serde_json::to_vec(self).map_err(|e| e.to_string())?;
                tx.send(PersistJob {
                    path: root.join("receive-audio.json"),
                    data,
                    error_code: "receive_audio_save_failed",
                })
                .map_err(|e| e.to_string())
            });
        if let Err(e) = result {
            report_code(out, "receive_audio_save_failed", Some(e));
        }
        self.dirty = None;
        self.last_change = None;
    }
}
pub fn valid_identity(value: &str) -> bool {
    !value.trim().is_empty() && value.len() <= 256 && !value.chars().any(char::is_control)
}

struct User {
    uid: Option<String>,
    token: String,
    percent: u16,
    temporary_changed: bool,
    dsp: Option<Gain>,
}
#[derive(Default)]
pub struct Session {
    server: String,
    users: HashMap<ClientId, User>,
}
impl Session {
    pub fn clear(&mut self) {
        self.users.clear();
        self.server.clear();
    }
    pub fn remove(&mut self, id: ClientId) {
        self.users.remove(&id);
    }
    pub fn sync(
        &mut self,
        con: &Connection,
        prefs: &mut Preferences,
        audio: &mut crate::tsclientlib_audio::AudioHandler,
    ) {
        let Ok(state) = con.get_state() else {
            return;
        };
        let server = state.server.public_key.get_uid();
        if server != self.server {
            self.clear();
            self.server = server;
            audio.reset();
        }
        self.users.retain(|id, _| {
            (state.clients.contains_key(id) || audio.get_queues().contains_key(id))
                && *id != state.own_client
        });
        for client in state.clients.values().filter(|c| c.id != state.own_client) {
            let uid = client
                .uid
                .as_ref()
                .map(|u| u.as_ref().to_string())
                .filter(|u| valid_identity(u));
            if let Some(user) = self.users.get_mut(&client.id) {
                if user.uid == uid {
                    continue;
                }
                // A UID arriving for this still-present member can bind its temporary choice.
                if user.uid.is_none() && uid.is_some() && user.temporary_changed {
                    prefs.save_volume(&self.server, uid.as_deref().unwrap(), user.percent);
                } else {
                    user.percent = prefs.volume(&self.server, uid.as_deref());
                }
                let identity_changed = user.uid.is_some();
                user.uid = uid;
                user.token = token();
                if identity_changed {
                    user.dsp = None;
                    audio.get_mut_queues().remove(&client.id);
                }
            } else {
                self.users.insert(
                    client.id,
                    User {
                        percent: prefs.volume(&self.server, uid.as_deref()),
                        uid,
                        token: token(),
                        temporary_changed: false,
                        dsp: None,
                    },
                );
            }
        }
        for user in self.users.values_mut() {
            if let Some(uid) = user.uid.as_deref() {
                user.percent = prefs.volume(&self.server, Some(uid));
            }
        }
        audio
            .get_mut_queues()
            .retain(|id, _| self.users.contains_key(id));
    }
    pub fn fields(&self, id: ClientId) -> (u16, Option<&str>) {
        self.users
            .get(&id)
            .map_or((100, None), |u| (u.percent, Some(u.token.as_str())))
    }
    pub fn set_volume(
        &mut self,
        server: &str,
        client: u16,
        uid: Option<&str>,
        expected: &str,
        percent: u16,
        prefs: &mut Preferences,
    ) -> Result<(), &'static str> {
        if percent > MAX_VOLUME_PERCENT || server != self.server {
            return Err("receive_audio_context_changed");
        }
        let user = self
            .users
            .get_mut(&ClientId(client))
            .ok_or("receive_audio_context_changed")?;
        if user.token != expected || user.uid.as_deref() != uid {
            return Err("receive_audio_context_changed");
        }
        if !prefs.writable {
            return Err("receive_audio_read_failed");
        }
        user.percent = percent;
        if let Some(uid) = uid {
            prefs.save_volume(server, uid, percent);
            for user in self
                .users
                .values_mut()
                .filter(|u| u.uid.as_deref() == Some(uid))
            {
                user.percent = percent;
            }
        } else {
            user.temporary_changed = true;
        }
        Ok(())
    }
    pub fn prepare_audio(&mut self, id: ClientId) {
        let user = self.users.entry(id).or_insert_with(|| User {
            uid: None,
            token: token(),
            percent: 100,
            temporary_changed: false,
            dsp: None,
        });
        if user.dsp.is_none() {
            user.dsp = Some(Gain::new());
        }
    }
    pub fn process(&mut self, id: &ClientId, pcm: &mut [f32], agc: bool) {
        if let Some(user) = self.users.get_mut(id) {
            if let Some(dsp) = &mut user.dsp {
                dsp.process(pcm, agc);
            }
        } else {
            pcm.fill(0.0);
        }
    }
}

pub struct Gain {
    detector: Box<DenoiseState<'static>>,
    analysis: [f32; 480],
    position: usize,
    power: f32,
    level: Option<f32>,
    noise: f32,
    voiced: usize,
    desired_db: f32,
    gain_db: f32,
    bypass: f32,
    limiter: Limiter,
}
impl Gain {
    fn new() -> Self {
        Self {
            detector: DenoiseState::new(),
            analysis: [0.; 480],
            position: 0,
            power: 0.,
            level: None,
            noise: 1e-8,
            voiced: 0,
            desired_db: 0.,
            gain_db: 0.,
            bypass: 1.,
            limiter: Limiter::default(),
        }
    }
    fn analyze(&mut self) {
        let power = self.power / 480.;
        let rms = power.sqrt();
        // Analysis-only normalization helps the classifier hear quiet speech; never affects PCM.
        let analysis_gain = (0.08 / rms.max(1e-6)).clamp(1., 10.);
        for v in &mut self.analysis {
            *v *= analysis_gain * 32768.;
        }
        let mut clean = [0.; 480];
        let probability = self.detector.process_frame(&mut clean, &self.analysis);
        let speech = probability.is_finite()
            && probability >= 0.6
            && rms >= 0.001
            // Strong speech confidence can recover from a stale, previously louder noise floor.
            && (power >= self.noise * 4. || probability >= 0.9);
        if speech {
            self.voiced = (self.voiced + 1).min(8);
            let level = self.level.get_or_insert(power);
            let tau = if power > *level { 0.08 } else { 0.4 };
            *level += (power - *level) * (1. - (-0.01f32 / tau).exp());
            if self.voiced >= 8 {
                self.desired_db =
                    (TARGET_DB - 10. * level.max(1e-12).log10()).clamp(MIN_DB, MAX_DB);
            }
        } else {
            self.voiced = 0;
            // Only non-speech updates the floor; slow rise avoids swallowing a quiet onset.
            let tau = if power < self.noise { 0.1 } else { 3.0 };
            self.noise += (power - self.noise) * (1. - (-0.01f32 / tau).exp());
            // Freeze increases immediately in silence/noise, preserving short-pause gain.
            self.desired_db = self.desired_db.min(self.gain_db);
        }
        self.position = 0;
        self.power = 0.;
    }
    fn process(&mut self, pcm: &mut [f32], enabled: bool) {
        for pair in pcm.chunks_exact_mut(2) {
            for v in pair.iter_mut() {
                *v = finite(*v);
            }
            let mono = if pair[0].abs() >= pair[1].abs() {
                pair[0]
            } else {
                pair[1]
            };
            self.analysis[self.position] = mono;
            self.power += (pair[0] * pair[0] + pair[1] * pair[1]) * 0.5;
            self.position += 1;
            if self.position == 480 {
                self.analyze();
            }
            let step = if self.desired_db < self.gain_db {
                60.
            } else {
                3.
            } / 48000.;
            self.gain_db += (self.desired_db - self.gain_db).clamp(-step, step);
            self.bypass +=
                ((if enabled { 1. } else { 0. }) - self.bypass) * (1. - (-1.0f32 / 1440.).exp());
            let gain = 10.0f32.powf(self.gain_db * self.bypass / 20.);
            pair[0] *= gain;
            pair[1] *= gain;
            self.limiter.process(pair);
        }
        if pcm.len() % 2 != 0 {
            *pcm.last_mut().unwrap() = 0.;
        }
    }
}
fn finite(value: f32) -> f32 {
    if value.is_finite() {
        value.clamp(-8., 8.)
    } else {
        0.
    }
}
pub struct Limiter {
    gain: f32,
}
impl Default for Limiter {
    fn default() -> Self {
        Self { gain: 1. }
    }
}
impl Limiter {
    pub fn process(&mut self, pcm: &mut [f32]) {
        for pair in pcm.chunks_exact_mut(2) {
            for v in pair.iter_mut() {
                if !v.is_finite() {
                    *v = 0.;
                }
            }
            let peak = pair[0].abs().max(pair[1].abs());
            let required = if peak > 0.95 { 0.95 / peak } else { 1. };
            self.gain = required.min(self.gain + (1. - self.gain) * (1. - (-1.0f32 / 3840.).exp()));
            pair[0] *= self.gain;
            pair[1] *= self.gain;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::atomic_write;
    fn speech() -> Vec<f32> {
        include_bytes!("../tests/fixtures/speech.pcm")
            .chunks_exact(2)
            .map(|p| i16::from_le_bytes([p[0], p[1]]) as f32 / 32768.)
            .collect()
    }
    #[test]
    fn speech_converges_independently_and_manual_gain_is_not_cancelled() {
        let input = speech();
        let mut gains = [Gain::new(), Gain::new(), Gain::new()];
        for _ in 0..12 {
            for block in input.chunks(480) {
                for (i, gain) in gains.iter_mut().enumerate() {
                    let scale = [0.3, 1., 0.3][i];
                    let mut pcm: Vec<_> = block
                        .iter()
                        .flat_map(|v| [v * scale, v * scale * 0.5])
                        .collect();
                    gain.process(&mut pcm, true);
                    assert!(pcm.iter().all(|v| v.is_finite() && v.abs() <= 0.951));
                }
            }
        }
        let levels: Vec<_> = gains
            .iter()
            .map(|g| 10. * g.level.unwrap().log10() + g.gain_db)
            .collect();
        assert!((levels[0] - TARGET_DB).abs() < 3., "{levels:?}");
        assert!((levels[1] - TARGET_DB).abs() < 3., "{levels:?}");
        assert!((gains[0].gain_db - gains[2].gain_db).abs() < 0.001);
        // Manual gain is applied by AudioQueue after this identical per-user analysis.
    }
    #[test]
    fn silence_noise_limits_bypass_and_stereo_are_safe_for_any_blocks() {
        let mut gain = Gain::new();
        let mut random = 1u32;
        for _ in 0..400 {
            let mut pcm = [0.; 960];
            for pair in pcm.chunks_exact_mut(2) {
                random = random.wrapping_mul(1664525).wrapping_add(1013904223);
                let n = ((random >> 8) as f32 / 16777216. - 0.5) * 0.02;
                pair.copy_from_slice(&[n, -n]);
            }
            gain.process(&mut pcm, true);
        }
        assert!(gain.gain_db < 0.5, "noise gained {} dB", gain.gain_db);
        for size in [2, 6, 254, 960, 1920, 5760] {
            let mut pcm = vec![0.; size];
            gain.process(&mut pcm, true);
            assert!(pcm.iter().all(|v| *v == 0.));
        }
        for _ in 0..100 {
            let mut pcm = [0.1; 960];
            gain.process(&mut pcm, false);
        }
        let mut pcm = [0.1; 960];
        gain.process(&mut pcm, false);
        assert!((pcm[950] - 0.1).abs() < 0.0001);
        let mut pcm = [f32::NAN, f32::INFINITY, 1., -0.5, 100., -100.];
        gain.process(&mut pcm, true);
        assert!(pcm.iter().all(|v| v.is_finite() && v.abs() <= 0.951));
        assert!((pcm[2] + pcm[3] * 2.).abs() < 0.001);
        assert!(gain.gain_db >= MIN_DB && gain.gain_db <= MAX_DB);
    }
    #[test]
    fn block_boundaries_sudden_peaks_and_gain_rates() {
        let input: Vec<_> = speech().iter().flat_map(|v| [v * 0.1, -v * 0.05]).collect();
        let mut whole = input.clone();
        let mut split = input;
        let mut a = Gain::new();
        let mut b = Gain::new();
        a.process(&mut whole, true);
        let mut offset = 0;
        for size in [2, 58, 960, 126, 5760].into_iter().cycle() {
            if offset == split.len() {
                break;
            }
            let end = (offset + size).min(split.len());
            b.process(&mut split[offset..end], true);
            offset = end;
        }
        assert_eq!(whole, split);
        assert_eq!(a.gain_db, b.gain_db);
        a.gain_db = MAX_DB;
        a.desired_db = MIN_DB;
        let mut burst = [0.9; 960];
        a.process(&mut burst, true);
        assert!(burst.iter().all(|v| v.abs() <= 0.951));
        assert!(a.gain_db < MAX_DB && a.gain_db >= MAX_DB - 0.61);
        a.desired_db = MAX_DB;
        let before = a.gain_db;
        let mut quiet = [0.; 960];
        a.process(&mut quiet, true);
        assert!(a.gain_db <= before + 0.031);
        let mut limiter = Limiter::default();
        let mut mix = [4., -2., f32::NAN, 0.];
        limiter.process(&mut mix);
        assert!(mix.iter().all(|v| v.is_finite() && v.abs() <= 0.951));
        assert!((mix[0] + 2. * mix[1]).abs() < 1e-6);
    }
    #[test]
    fn confident_quiet_speech_recovers_after_a_high_noise_floor() {
        let input = speech();
        // Continuous voiced blocks at a constant low RMS, after louder background noise.
        let blocks: Vec<Vec<f32>> = input
            .chunks_exact(480)
            .filter_map(|block| {
                let rms = (block.iter().map(|v| v * v).sum::<f32>() / 480.).sqrt();
                (rms > 0.01).then(|| block.iter().flat_map(|v| [v * 0.02 / rms; 2]).collect())
            })
            .collect();
        assert!(!blocks.is_empty());
        let mut gain = Gain::new();
        gain.noise = 0.01;
        for _ in 0..12 {
            for block in &blocks {
                let mut pcm = block.clone();
                gain.process(&mut pcm, true);
            }
        }
        assert!(
            gain.gain_db > 6.,
            "quiet speech remains at {} dB",
            gain.gain_db
        );
        assert!((10. * gain.level.unwrap().log10() + gain.gain_db - TARGET_DB).abs() < 1.);
    }
    #[test]
    fn persistence_defaults_corruption_and_identity_scope() {
        let root = std::env::temp_dir().join(format!("mobilespeak-receive-{}", token()));
        let out = Arc::new(Mutex::new(Output::default()));
        let mut prefs = Preferences::default();
        prefs.load(&root, &out);
        assert!(prefs.agc);
        prefs.save_volume("server-a", "user", 143);
        prefs.save_volume("server-b", "user", 45);
        prefs.agc = false;
        atomic_write(
            &root.join("receive-audio.json"),
            &serde_json::to_vec(&prefs).unwrap(),
        )
        .unwrap();
        let mut loaded = Preferences::default();
        loaded.load(&root, &out);
        assert!(!loaded.agc);
        assert_eq!(loaded.volume("server-a", Some("user")), 143);
        assert_eq!(loaded.volume("server-b", Some("user")), 45);
        assert_eq!(loaded.volume("server-a", Some("new")), 100);
        let mut session = Session {
            server: "server-a".into(),
            ..Default::default()
        };
        session.users.insert(
            ClientId(7),
            User {
                uid: Some("user".into()),
                token: "one".into(),
                percent: 143,
                temporary_changed: false,
                dsp: None,
            },
        );
        assert!(session
            .set_volume("server-a", 7, Some("other"), "one", 200, &mut loaded)
            .is_err());
        assert!(session
            .set_volume("server-b", 7, Some("user"), "one", 200, &mut loaded)
            .is_err());
        assert!(session
            .set_volume("server-a", 7, Some("user"), "old", 200, &mut loaded)
            .is_err());
        assert!(session
            .set_volume("server-a", 7, Some("user"), "one", 301, &mut loaded)
            .is_err());
        for value in 0..=300 {
            session
                .set_volume("server-a", 7, Some("user"), "one", value, &mut loaded)
                .unwrap();
        }
        let (tx, rx) = mpsc::channel();
        loaded.flush(Some(&root), &tx, &out, false);
        assert!(rx.try_recv().is_err(), "dragging must not write each tick");
        loaded.flush(Some(&root), &tx, &out, true);
        let job = rx.try_recv().unwrap();
        atomic_write(&job.path, &job.data).unwrap();
        let mut final_value = Preferences::default();
        final_value.load(&root, &out);
        assert_eq!(final_value.volume("server-a", Some("user")), 300);
        assert!(!final_value.agc);
        session.remove(ClientId(7));
        assert_eq!(loaded.volume("server-a", Some("user")), 300);
        session.users.insert(
            ClientId(7),
            User {
                uid: None,
                token: "new".into(),
                percent: 100,
                temporary_changed: false,
                dsp: None,
            },
        );
        assert!(session
            .set_volume("server-a", 7, Some("user"), "one", 50, &mut loaded)
            .is_err());
        session
            .set_volume("server-a", 7, None, "new", 160, &mut loaded)
            .unwrap();
        assert_eq!(loaded.volume("server-a", None), 100);
        assert_eq!(loaded.volume("server-a", Some("user")), 300);
        loaded.set_agc(true, &out);
        assert_eq!(session.fields(ClientId(7)).0, 160);
        atomic_write(&root.join("receive-audio.json"), b"{}").unwrap();
        loaded.load(&root, &out);
        assert!(loaded.agc);
        let invalid = br#"{"users":{"server-a":{"user":301}}}"#;
        atomic_write(&root.join("receive-audio.json"), invalid).unwrap();
        loaded.load(&root, &out);
        assert!(!loaded.writable);
        assert_eq!(std::fs::read(root.join("receive-audio.json")).unwrap(), invalid);
        atomic_write(&root.join("receive-audio.json"), b"broken").unwrap();
        loaded.load(&root, &out);
        assert!(!loaded.writable);
        std::fs::remove_dir_all(root).unwrap();
    }
}
