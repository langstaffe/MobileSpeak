//! Local transmit intent. Tokens bind a touch to one uninterrupted audio context.
use crate::Output;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::sync::{
    atomic::{AtomicU64, Ordering},
    Arc, Mutex,
};

static NEXT_TOKEN: AtomicU64 = AtomicU64::new(1);
fn token() -> String {
    NEXT_TOKEN.fetch_add(1, Ordering::Relaxed).to_string()
}

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    #[default]
    Automatic,
    PushToTalk,
}

pub struct State {
    pub mode: Mode,
    pub capture_ready: bool,
    available: bool,
    pressed: bool,
    token: String,
}
impl Default for State {
    fn default() -> Self {
        Self {
            mode: Mode::default(),
            capture_ready: false,
            available: false,
            pressed: false,
            token: token(),
        }
    }
}
impl State {
    pub fn invalidate(&mut self) {
        self.pressed = false;
        self.token = token();
    }
    pub fn set_mode(&mut self, mode: Mode) {
        if self.mode != mode {
            self.mode = mode;
            self.invalidate();
        }
    }
    pub fn available(&mut self, available: bool) -> bool {
        let interrupted = self.available && !available;
        if self.available != available {
            self.invalidate();
        }
        self.available = available;
        interrupted
    }
    pub fn press(&mut self, pressed: bool, token: &str) -> bool {
        if token != self.token || self.mode != Mode::PushToTalk {
            return false;
        }
        if pressed {
            if !self.available || self.pressed {
                return false;
            }
            self.pressed = true;
        } else {
            self.invalidate();
        }
        true
    }
    pub fn sending_allowed(&self) -> bool {
        self.mode == Mode::Automatic || (self.available && self.pressed)
    }
    pub fn publish(&self, transmitting: bool, out: &Arc<Mutex<Output>>) {
        let value = json!({"mode":self.mode,"token":self.token,"available":self.available,"transmitting":transmitting});
        let mut out = out.lock().unwrap();
        if out.speech != value {
            out.speech = value;
            out.notify();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn release_cancel_and_context_invalidation_require_a_new_press() {
        let mut state = State::default();
        assert!(state.sending_allowed());
        state.set_mode(Mode::PushToTalk);
        let unavailable = state.token.clone();
        assert!(!state.press(true, &unavailable));
        state.available(true);
        assert!(!state.press(true, &unavailable)); // late permission/audio startup callback
        let touch = state.token.clone();
        assert!(state.press(true, &touch));
        assert!(state.sending_allowed());
        assert!(state.press(false, &touch));
        assert!(!state.sending_allowed());
        assert!(!state.press(true, &unavailable));
        let touch = state.token.clone();
        assert!(state.press(true, &touch));
        state.available(false); // mute, interruption, background, DSP switch
        state.available(true);
        assert!(!state.sending_allowed());
        assert!(!state.press(true, &touch));
        let next = state.token.clone();
        assert!(state.press(true, &next));
        assert!(!state.press(false, &touch)); // a departed view cannot end a new touch
        state.invalidate(); // channel switch / reconnect, even if availability stays true
        assert!(!state.sending_allowed());
        assert!(!state.press(true, &next));
        state.set_mode(Mode::Automatic);
        assert!(state.sending_allowed());
        state.set_mode(Mode::PushToTalk);
        assert!(!state.sending_allowed());
    }
}
