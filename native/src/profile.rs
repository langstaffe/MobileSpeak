//! One installation's durable nickname, independent of the server's confirmed name.
use crate::{atomic_write, report_code, Output};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    fs::File,
    io::Read,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
};

pub const DEFAULT_NICKNAME: &str = "MobileSpeakUser";

pub fn normalize(value: &str) -> Result<&str, &'static str> {
    let value = value.trim();
    if value.is_empty() || value.len() > 128 {
        Err("nickname_invalid")
    } else {
        Ok(value)
    }
}

#[derive(Deserialize, Serialize)]
struct Config {
    nickname: String,
}

pub struct Profile {
    pub nickname: String,
    pub revision: u64,
    request: u64,
    root: Option<PathBuf>,
    blocked: bool,
    error: Option<&'static str>,
    detail: Option<String>,
}

impl Default for Profile {
    fn default() -> Self {
        Self {
            nickname: DEFAULT_NICKNAME.into(),
            revision: 0,
            request: 0,
            root: None,
            blocked: false,
            error: None,
            detail: None,
        }
    }
}

impl Profile {
    pub fn snapshot(&self) -> Value {
        json!({"nickname":self.nickname,"requestId":self.request,"error":self.error,"detail":self.detail})
    }

    pub fn publish(&self, out: &Arc<Mutex<Output>>) {
        let mut out = out.lock().unwrap();
        let value = self.snapshot();
        if out.profile != value {
            out.profile = value;
            out.notify();
        }
    }

    pub fn load(&mut self, root: &Path, out: &Arc<Mutex<Output>>) {
        if self.root.is_some() {
            return;
        }
        self.root = Some(root.to_owned());
        let read = || -> Result<Option<String>, String> {
            let file = match File::open(root.join("profile.json")) {
                Ok(file) => file,
                Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                    match std::fs::symlink_metadata(root.join("profile.json")) {
                        Err(missing) if missing.kind() == std::io::ErrorKind::NotFound => {
                            return Ok(None)
                        }
                        _ => return Err(e.to_string()),
                    }
                }
                Err(e) => return Err(e.to_string()),
            };
            let mut data = Vec::new();
            file.take(4097)
                .read_to_end(&mut data)
                .map_err(|e| e.to_string())?;
            if data.len() > 4096 {
                return Err("Profile file is too large".into());
            }
            let config: Config = serde_json::from_slice(&data).map_err(|e| e.to_string())?;
            Ok(Some(
                normalize(&config.nickname)
                    .map_err(str::to_owned)?
                    .to_owned(),
            ))
        };
        match read() {
            Ok(Some(name)) => self.nickname = name,
            Ok(None) => {}
            Err(detail) => {
                self.blocked = true;
                self.error = Some("profile_read_failed");
                self.detail = Some(detail.clone());
                report_code(out, "profile_read_failed", Some(detail));
            }
        }
        self.publish(out);
    }

    // Commands are serialized by the core; a lower request cannot supersede a newer intent.
    pub fn save(&mut self, value: &str, request: u64, out: &Arc<Mutex<Output>>) -> bool {
        if request <= self.request {
            return false;
        }
        self.request = request;
        let save = || -> Result<String, (&'static str, Option<String>)> {
            let name = normalize(value).map_err(|code| (code, None))?;
            if self.blocked {
                return Err(("profile_read_failed", self.detail.clone()));
            }
            let root = self.root.as_ref().ok_or((
                "profile_save_failed",
                Some("Storage is not configured".into()),
            ))?;
            let data = serde_json::to_vec(&Config {
                nickname: name.into(),
            })
            .map_err(|e| ("profile_save_failed", Some(e.to_string())))?;
            atomic_write(&root.join("profile.json"), &data)
                .map_err(|e| ("profile_save_failed", Some(e.to_string())))?;
            Ok(name.to_owned())
        };
        let saved = match save() {
            Ok(name) => {
                self.nickname = name;
                self.revision += 1;
                self.error = None;
                self.detail = None;
                true
            }
            Err((code, detail)) => {
                self.error = Some(code);
                self.detail = detail;
                false
            }
        };
        self.publish(out);
        saved
    }
}

#[derive(Default)]
pub struct Application {
    queued: bool,
    next_token: u64,
    pending: Option<(u64, u64)>, // token, saved profile revision
}

impl Application {
    pub fn changed(&mut self) {
        self.queued = true;
    }
    pub fn disconnected(&mut self) {
        self.queued = false;
        self.pending = None;
    }
    pub fn plan(&mut self, profile: &Profile, actual: Option<&str>) -> Option<u64> {
        if !self.queued || self.pending.is_some() {
            return None;
        }
        let actual = actual?;
        self.queued = false;
        if actual == profile.nickname {
            return None;
        }
        self.next_token += 1;
        self.pending = Some((self.next_token, profile.revision));
        Some(self.next_token)
    }
    pub fn finished(&mut self, token: u64, profile: &Profile) -> bool {
        if let Some((pending, revision)) = self.pending {
            if token == pending {
                self.pending = None;
                return revision == profile.revision;
            }
        }
        false
    }
    pub fn result(
        &mut self,
        token: u64,
        profile: &Profile,
        result: Result<(), String>,
        out: &Arc<Mutex<Output>>,
    ) {
        if self.finished(token, profile) {
            if let Err(detail) = result {
                report_code(out, "profile_rename_failed", Some(detail));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    fn root() -> PathBuf {
        std::env::temp_dir().join(format!(
            "mobilespeak-profile-{}-{}",
            std::process::id(),
            crate::MESSAGE_SEQUENCE.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ))
    }
    #[test]
    fn nickname_unicode_whitespace_and_byte_boundaries() {
        assert_eq!(normalize(" \u{85} 用户😀 \n"), Ok("用户😀"));
        assert!(normalize("\u{2003}\n").is_err());
        assert!(normalize(&"😀".repeat(32)).is_ok());
        assert!(normalize(&format!("{}a", "😀".repeat(32))).is_err());
        assert!(normalize(&"a".repeat(128)).is_ok());
        assert!(normalize(&"a".repeat(129)).is_err());
        // Preserve the existing rule: no additional character blacklist.
        assert_eq!(normalize("a\tb"), Ok("a\tb"));
    }
    #[test]
    fn defaults_save_restart_and_failures_preserve_data() {
        let root = root();
        fs::create_dir_all(&root).unwrap();
        let out = Arc::new(Mutex::new(Output::default()));
        let mut profile = Profile::default();
        profile.load(&root, &out);
        assert_eq!(profile.nickname, DEFAULT_NICKNAME);
        assert!(!root.join("profile.json").exists());
        assert!(profile.save(" 用户😀 ", 1, &out));
        let saved = fs::read(root.join("profile.json")).unwrap();
        let mut restarted = Profile::default();
        restarted.load(&root, &out);
        assert_eq!(restarted.nickname, "用户😀");
        restarted.load(&root, &out);
        assert_eq!(restarted.nickname, "用户😀");
        assert!(!restarted.save("  ", 1, &out));
        assert_eq!(fs::read(root.join("profile.json")).unwrap(), saved);
        fs::create_dir(root.join("profile.tmp")).unwrap();
        assert!(!restarted.save("Failed", 2, &out));
        assert_eq!(restarted.nickname, "用户😀");
        assert_eq!(fs::read(root.join("profile.json")).unwrap(), saved);
        fs::remove_dir(root.join("profile.tmp")).unwrap();
        assert!(restarted.save("Retry", 3, &out));
        fs::write(root.join("profile.json"), "broken").unwrap();
        let mut corrupt = Profile::default();
        corrupt.load(&root, &out);
        assert!(!corrupt.save("Do not overwrite", 1, &out));
        assert_eq!(
            fs::read_to_string(root.join("profile.json")).unwrap(),
            "broken"
        );
        assert_eq!(corrupt.error, Some("profile_read_failed"));
        fs::remove_file(root.join("profile.json")).unwrap();
        std::os::unix::fs::symlink(root.join("missing-profile"), root.join("profile.json"))
            .unwrap();
        let mut unreadable = Profile::default();
        unreadable.load(&root, &out);
        assert!(!unreadable.save("Do not replace a broken link", 1, &out));
        assert!(fs::symlink_metadata(root.join("profile.json"))
            .unwrap()
            .file_type()
            .is_symlink());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn latest_intent_rejection_and_cross_connection_results() {
        let root = root();
        let out = Arc::new(Mutex::new(Output::default()));
        let mut profile = Profile::default();
        profile.load(&root, &out);
        let mut application = Application::default();
        assert!(profile.save("A", 1, &out));
        application.changed();
        let a = application.plan(&profile, Some("Actual")).unwrap();
        assert!(profile.save("B", 2, &out));
        application.changed();
        assert!(profile.save("C", 3, &out));
        application.changed();
        assert!(application.plan(&profile, Some("Actual")).is_none());
        assert!(!application.finished(a, &profile)); // Old refusal must not become C's refusal.
        let c = application.plan(&profile, Some("A")).unwrap();
        assert!(application.finished(c, &profile));
        assert_eq!(profile.nickname, "C"); // Server refusal never rolls back durable intent.
        assert!(application.plan(&profile, Some("Admin rename")).is_none()); // No rename loop.
        application.changed();
        let old = application.plan(&profile, Some("Actual")).unwrap();
        application.disconnected();
        application.changed();
        let next = application.plan(&profile, Some("Actual")).unwrap();
        assert!(!application.finished(old, &profile));
        assert!(application.finished(next, &profile));
        assert!(!profile.save("Late B", 2, &out));
        assert_eq!(profile.nickname, "C");
        application.changed();
        assert!(application.plan(&profile, None).is_none());
        assert!(application.plan(&profile, Some("C")).is_none());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn rename_success_and_refusal_keep_global_and_confirmed_names_separate() {
        let out = Arc::new(Mutex::new(Output::default()));
        out.lock().unwrap().connection_id = Some("connection-one".into());
        out.lock().unwrap().snapshot = json!({"clients":[{"name":"Server confirmed"}]});
        let mut profile = Profile::default();
        profile.nickname = "Saved global".into();
        profile.revision = 1;
        let mut application = Application::default();
        application.changed();
        let success = application
            .plan(&profile, Some("Server confirmed"))
            .unwrap();
        application.result(success, &profile, Ok(()), &out);
        assert!(out.lock().unwrap().events.is_empty());
        application.changed();
        let rejected = application
            .plan(&profile, Some("Server confirmed"))
            .unwrap();
        application.result(
            rejected,
            &profile,
            Err("Server refused nickname (controlled test)".into()),
            &out,
        );
        assert_eq!(
            out.lock().unwrap().events[0]["code"],
            "profile_rename_failed"
        );
        assert_eq!(
            out.lock().unwrap().events[0]["connectionId"],
            "connection-one"
        );
        assert_eq!(profile.nickname, "Saved global");
        assert_eq!(
            out.lock().unwrap().snapshot["clients"][0]["name"],
            "Server confirmed"
        );
        assert!(application
            .plan(&profile, Some("Admin changed it"))
            .is_none());
        application.changed();
        let old = application
            .plan(&profile, Some("Server confirmed"))
            .unwrap();
        application.disconnected();
        out.lock().unwrap().connection_id = Some("connection-two".into());
        let events = out.lock().unwrap().events.len();
        application.result(old, &profile, Err("Late rejection".into()), &out);
        assert_eq!(out.lock().unwrap().events.len(), events);
    }
}
