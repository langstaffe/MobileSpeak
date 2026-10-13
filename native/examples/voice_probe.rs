//! Two temporary identities verify decoded speech through a user-supplied test server.
//! Read a connect JSON object from stdin; never persist server credentials.
use mobilespeak_core::{ts_capture, ts_playback_acquire, ts_playback_active, ts_playback_release, ts_render, Bridge};
use serde_json::{json, Value};
use std::{ffi::c_void, io::{self, BufRead}, time::{Duration, Instant}};

struct Probe {
    bridge: Box<Bridge>,
    playback: *const c_void,
}
impl Probe {
    fn new() -> Self {
        let mut bridge = Box::new(Bridge::new());
        let playback = unsafe { ts_playback_acquire(&mut *bridge) };
        assert!(!playback.is_null());
        unsafe { ts_playback_active(playback, true); }
        Self { bridge, playback: playback.cast() }
    }
    fn send(&self, value: Value) -> Result<(), String> { self.bridge.send(&value.to_string()) }
    fn read(&self) -> Result<Value, String> {
        let envelope = self.bridge.poll();
        for event in envelope["events"].as_array().unwrap() {
            if event["type"] == "error" || event["type"] == "audio_muted" {
                return Err(format!("{}: {}", event["code"].as_str().unwrap_or("unknown"), event["detail"].as_str().unwrap_or("no detail")));
            }
        }
        Ok(envelope["snapshot"].clone())
    }
    fn render(&self) -> usize {
        let mut pcm = [0f32; 1920];
        unsafe { ts_render(self.playback.cast(), pcm.as_mut_ptr(), pcm.as_mut_ptr().add(1), 960, 2); }
        pcm.iter().filter(|v| v.is_finite() && v.abs() > 0.0001).count()
    }
}
impl Drop for Probe {
    fn drop(&mut self) {
        let _ = self.send(json!({"type":"capture_stopped"}));
        let _ = self.send(json!({"type":"disconnect"}));
        unsafe { ts_playback_active(self.playback.cast(), false); ts_playback_release(self.playback.cast()); }
    }
}
fn speech() -> Vec<i16> {
    include_bytes!("../tests/fixtures/speech.pcm").chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]])).collect()
}
fn run(sender: &mut Probe, receiver: &Probe, mut config: Value) -> Result<(), String> {
    config["type"] = json!("connect"); config["connectionId"] = json!("probe-sender"); config["name"] = json!("MobileSpeak-Probe-A");
    sender.send(config.clone())?;
    config["connectionId"] = json!("probe-receiver"); config["name"] = json!("MobileSpeak-Probe-B");
    receiver.send(config)?;
    let deadline = Instant::now() + Duration::from_secs(35);
    loop {
        let a = sender.read()?; let b = receiver.read()?;
        sender.render(); receiver.render();
        if a["status"] == "connected" && b["status"] == "connected" {
            let channel = |s: &Value| s["clients"].as_array().and_then(|rows| rows.iter().find(|c| c["id"] == s["ownClient"])).and_then(|c| c["channel"].as_u64());
            if let (Some(a), Some(b)) = (channel(&a), channel(&b)) {
                if a != b { receiver.send(json!({"type":"join", "channel":a}))?; }
                else { break; }
            }
        }
        if Instant::now() >= deadline { return Err("connect/channel timeout".into()); }
        std::thread::sleep(Duration::from_millis(20));
    }
    sender.send(json!({"type":"set_noise_suppression", "mode":"none"}))?;
    sender.send(json!({"type":"mute", "input":false,"output":false}))?;
    let samples = speech(); let frames: Vec<_> = samples.chunks_exact(960).collect();
    let mut audible_samples = 0;
    for frame in frames.iter().cycle().take(500) {
        let snapshot = sender.read()?; receiver.read()?;
        if snapshot["canSend"] == true {
            let result = unsafe { ts_capture(&mut *sender.bridge, frame.as_ptr(), frame.len()) };
            if result != 0 { return Err(format!("capture rejected: {result}")); }
        }
        sender.render(); audible_samples += receiver.render();
        std::thread::sleep(Duration::from_millis(20));
    }
    if audible_samples < 1920 { return Err(format!("insufficient decoded PCM: {audible_samples} nonzero samples")); }
    println!("PASS: received {audible_samples} decoded speech samples through the server");
    Ok(())
}
fn main() {
    let result = (|| -> Result<(), String> {
        let line = io::stdin().lock().lines().next().ok_or("connect JSON required on stdin")?.map_err(|e| e.to_string())?;
        let config = serde_json::from_str(&line).map_err(|e| e.to_string())?;
        let mut sender = Probe::new(); let receiver = Probe::new();
        let result = run(&mut sender, &receiver, config);
        for client in [&sender, &receiver] { let _ = client.send(json!({"type":"capture_stopped"})); client.send(json!({"type":"disconnect"}))?; }
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            if sender.bridge.poll()["snapshot"]["status"] == "disconnected" && receiver.bridge.poll()["snapshot"]["status"] == "disconnected" { break; }
            if Instant::now() >= deadline { return Err("disconnect timeout".into()); }
            std::thread::sleep(Duration::from_millis(20));
        }
        result
    })();
    if let Err(error) = result { eprintln!("FAIL: {error}"); std::process::exit(1); }
}
#[test]
fn fixture_is_complete_nonzero_speech() {
    let samples = speech();
    assert!(samples.len() >= 48000);
    assert!(samples.iter().filter(|v| v.unsigned_abs() > 100).count() > 9600);
}
