//! Mobile-only C ABI. TeamSpeak protocol state, chat and file transfers live on one Rust worker.
// Xcode's stderr transport can disappear after debugger/network disconnection.
// Diagnostics must never panic or terminate a connection when that write fails.
macro_rules! diagnostic {
    ($($argument:tt)*) => {{
        use std::io::Write as _;
        let _ = writeln!(std::io::stderr().lock(), $($argument)*);
    }};
}
#[cfg(target_os = "android")]
mod android_jni;
mod audio_models;
mod audio_processing;
mod avatar;
mod badges;
mod channel_files;
mod chat;
mod media;
#[cfg(feature = "network-diagnostics")]
mod network_diagnostics;
mod network_quality;
mod playback;
mod profile;
mod receive_audio;
// Fixed upstream receive module with the queued-packet recovery patch.
#[allow(dead_code)]
mod tsclientlib_audio;
use tsclientlib_audio::AudioHandler;
mod voice;
mod speech;

use badges::{known_badges, BadgeMetadata};
use futures::{FutureExt, StreamExt};
use nnnoiseless::DenoiseState;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    ffi::{c_char, CStr, CString},
    fs,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    sync::{mpsc as std_mpsc, Arc, Mutex},
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tokio::sync::mpsc;
use tsclientlib::prelude::*;
use tsclientlib::{
    events::Event, ChannelId, ChannelType, CodecEncryptionMode, Connection, DisconnectOptions,
    FiletransferHandle, Identity, MessageHandle, MessageTarget, OutCommandExt, StreamItem,
};
use tsproto_packets::packets::{AudioData, CodecType, OutAudio};

const MAX_IMAGE_BYTES: u64 = 5 * 1024 * 1024;
const MAX_MEDIA_TRANSFERS: usize = 4;
static MESSAGE_SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Command {
    Configure {
        storage: String,
    },
    Connect {
        #[serde(default, rename = "connectionId")]
        connection_id: Option<String>,
        address: String,
        #[serde(default)]
        password: String,
        identity: Option<Value>,
    },
    Join {
        channel: u64,
        #[serde(default)]
        password: String,
    },
    Mute {
        input: bool,
        output: bool,
    },
    CaptureStopped,
    SetCaptureReady { ready: bool },
    SetSpeechMode { mode: speech::Mode },
    PushToTalk { pressed: bool, token: String },
    #[serde(alias = "set_audio_processing")]
    SetNoiseSuppression {
        #[serde(alias = "noise")]
        mode: NoiseSuppressionMode,
        #[serde(default)]
        request_id: u64,
    },
    SetReceiveAgc {
        enabled: bool,
    },
    SetNickname {
        nickname: String,
        request_id: u64,
    },
    SetUserVolume {
        server: String,
        client: u16,
        uid: Option<String>,
        token: String,
        percent: u16,
    },
    SendChannelMessage {
        request_id: String,
        channel: u64,
        message: String,
    },
    SendPrivateMessage {
        request_id: String,
        client: u16,
        uid: String,
        message: String,
    },
    SetChatVisible {
        server: String,
        conversation: String,
        token: String,
        visible: bool,
    },
    SetAppActive {
        active: bool,
    },
    AvatarChanged,
    AvatarSelect {
        selection: u64,
    },
    AvatarSet {
        selection: u64,
        image: Option<String>,
    },
    FilesOpen {
        server: String,
        channel: u64,
    },
    FilesBack {
        server: String,
        channel: u64,
        directory: String,
    },
    FilesList {
        server: String,
        channel: u64,
        directory: String,
        path: String,
    },
    FilesSort {
        server: String,
        channel: u64,
        directory: String,
        newest: bool,
    },
    FilesDownload {
        server: String,
        channel: u64,
        path: String,
        name: String,
    },
    FilesUpload {
        server: String,
        channel: u64,
        path: String,
        name: String,
        source: String,
    },
    FilesRetry {
        id: u64,
    },
    FileCacheInspect {
        request_id: u64,
    },
    FileCacheClear {
        request_id: u64,
    },
    Disconnect,
    Shutdown,
    #[cfg(test)]
    PanicSession,
}
#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum NoiseSuppressionMode {
    Dpdfnet2,
    #[default]
    #[serde(alias = "dpdfnet8")] // Migrate the retired experimental setting.
    Rnnoise,
    None,
}

// The capture bridge supplies one 20 ms, 48 kHz mono i16 packet. RNNoise consumes
// 10 ms float frames in the i16 amplitude range, so no extra buffering is needed.
fn denoise_packet(samples: &[i16], state: &mut DenoiseState<'_>) -> Option<([i16; 960], f32)> {
    if samples.len() != 960 {
        return None;
    }
    let mut output = [0i16; 960];
    let mut probability = 0f32;
    let mut input_frame = [0f32; DenoiseState::FRAME_SIZE];
    let mut output_frame = [0f32; DenoiseState::FRAME_SIZE];
    for (input, output) in samples
        .chunks_exact(DenoiseState::FRAME_SIZE)
        .zip(output.chunks_exact_mut(DenoiseState::FRAME_SIZE))
    {
        for (sample, value) in input.iter().zip(&mut input_frame) {
            *value = f32::from(*sample);
        }
        let voice = state.process_frame(&mut output_frame, &input_frame);
        if !voice.is_finite() {
            return None;
        }
        probability = probability.max(voice);
        for (value, sample) in output_frame.iter().zip(output) {
            if !value.is_finite() {
                return None;
            }
            *sample = value
                .round()
                .clamp(f32::from(i16::MIN), f32::from(i16::MAX)) as i16;
        }
    }
    Some((output, probability))
}

impl Command {
    fn validate(&self) -> Result<(), &'static str> {
        match self {
            Self::Configure { storage }
                if storage.len() > 4096
                    || !Path::new(storage).is_absolute()
                    || Path::new(storage).parent().is_none() =>
            {
                Err("Invalid storage path")
            }
            Self::Connect { connection_id: Some(id), .. } if id.is_empty() || id.len() > 128 => Err("Invalid connection ID"),
            Self::Connect {
                address,
                password,
                ..
            } if address.trim().is_empty()
                || address.len() > 1024
                || password.len() > 1024 =>
            {
                Err("Invalid server address or password")
            }
            Self::Join { channel, password } if *channel == 0 || password.len() > 1024 => {
                Err("Invalid channel")
            }
            Self::PushToTalk { token, .. } if token.is_empty() || token.len() > 64 => Err("Invalid speech token"),
            Self::SetUserVolume {
                server,
                client,
                uid,
                token,
                percent,
            } if !receive_audio::valid_identity(server)
                || *client == 0
                || uid
                    .as_ref()
                    .is_some_and(|u| !receive_audio::valid_identity(u))
                || token.is_empty()
                || token.len() > 64
                || *percent > receive_audio::MAX_VOLUME_PERCENT =>
            {
                Err("Invalid user volume")
            }
            Self::SendChannelMessage {
                request_id,
                channel,
                message,
            } if *channel == 0 || !valid_message(request_id, message) => Err("Invalid message"),
            Self::SendPrivateMessage {
                request_id,
                client,
                uid,
                message,
            } if *client == 0
                || uid.is_empty()
                || uid.len() > 256
                || !valid_message(request_id, message) =>
            {
                Err("Invalid message")
            }
            Self::SetChatVisible {
                server,
                conversation,
                token,
                ..
            } if server.is_empty()
                || server.len() > 256
                || conversation.is_empty()
                || conversation.len() > 512
                || token.is_empty()
                || token.len() > 64 =>
            {
                Err("Invalid chat view")
            }
            Self::FilesList { path, .. } if !channel_files::valid_path(path) => {
                Err("Invalid file path")
            }
            Self::FilesBack {
                server,
                channel,
                directory,
            }
            | Self::FilesList {
                server,
                channel,
                directory,
                ..
            }
            | Self::FilesSort {
                server,
                channel,
                directory,
                ..
            } if server.is_empty()
                || server.len() > 256
                || *channel == 0
                || !channel_files::valid_path(directory) =>
            {
                Err("Invalid file context")
            }
            Self::FilesOpen { server, channel }
                if server.is_empty() || server.len() > 256 || *channel == 0 =>
            {
                Err("Invalid file context")
            }
            Self::FilesDownload {
                server,
                channel,
                path,
                name,
            }
            | Self::FilesUpload {
                server,
                channel,
                path,
                name,
                ..
            } if server.is_empty()
                || server.len() > 256
                || *channel == 0
                || !channel_files::valid_path(path)
                || !channel_files::valid_name(name) =>
            {
                Err("Invalid file request")
            }
            Self::FilesUpload { source, .. }
                if source.len() > 4096 || !Path::new(source).is_absolute() =>
            {
                Err("Invalid upload source")
            }
            _ => Ok(()),
        }
    }
}
fn valid_message(request_id: &str, message: &str) -> bool {
    !request_id.is_empty()
        && request_id.len() <= 128
        && request_id.is_ascii()
        && !message.trim().is_empty()
        && message.len() <= 8192
}

#[derive(Clone, Copy, Deserialize, Serialize)]
#[serde(rename_all = "lowercase")]
enum ChatKind {
    Channel,
    Private,
}
#[derive(Clone, Copy, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "lowercase")]
enum ChatStatus {
    Received,
    Pending,
    Sent,
    Failed,
}
#[derive(Clone, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct ChatMessage {
    id: String,
    conversation: String,
    kind: ChatKind,
    target_id: String,
    target_name: String,
    sender_uid: String,
    sender_name: String,
    sender_avatar_hash: String,
    own: bool,
    text: String,
    timestamp: u64,
    status: ChatStatus,
    error: Option<String>,
}
#[derive(Default, Deserialize, Serialize)]
struct ChatStore {
    messages: Vec<ChatMessage>,
}
impl ChatStore {
    fn fail_pending(&mut self, reason: &str) -> bool {
        let mut changed = false;
        for message in &mut self.messages {
            if message.status == ChatStatus::Pending {
                message.status = ChatStatus::Failed;
                message.error = Some(reason.to_owned());
                changed = true;
            }
        }
        changed
    }
}

#[derive(Default)]
struct UnreadState {
    server: Option<String>,
    channel: Option<String>,
    channel_count: u32,
    private_counts: HashMap<String, u32>,
    visible: Option<(String, String)>,
    app_active: bool,
}
impl UnreadState {
    fn sync(&mut self, server: Option<String>, channel: Option<String>) {
        if server.is_none() {
            return;
        }
        if self.server != server {
            self.server = server;
            self.private_counts.clear();
            self.visible = None;
            self.channel = None;
            self.channel_count = 0;
        }
        if self.channel != channel {
            if self
                .visible
                .as_ref()
                .is_some_and(|(conversation, _)| conversation.starts_with("channel:"))
            {
                self.visible = None;
            }
            self.channel = channel;
            self.channel_count = 0;
        }
    }
    fn record(&mut self, message: &ChatMessage) {
        if self.server.is_none()
            || message.own
            || message.status != ChatStatus::Received
            || (self.app_active
                && self
                    .visible
                    .as_ref()
                    .is_some_and(|(conversation, _)| conversation == &message.conversation))
        {
            return;
        }
        match message.kind {
            ChatKind::Channel if self.channel.as_deref() == Some(message.conversation.as_str()) => {
                self.channel_count = self.channel_count.saturating_add(1);
            }
            ChatKind::Private
                if message.conversation == format!("client:{}", message.target_id) =>
            {
                let count = self
                    .private_counts
                    .entry(message.target_id.clone())
                    .or_default();
                *count = count.saturating_add(1);
            }
            _ => {}
        }
    }
    fn set_visible(&mut self, server: &str, conversation: &str, token: &str, visible: bool) {
        if self.server.as_deref() != Some(server) {
            return;
        }
        if visible {
            if self.channel.as_deref() != Some(conversation)
                && !conversation
                    .strip_prefix("client:")
                    .is_some_and(|uid| !uid.is_empty())
            {
                return;
            }
            self.visible = Some((conversation.to_owned(), token.to_owned()));
            if self.app_active {
                self.clear_visible();
            }
        } else if self
            .visible
            .as_ref()
            .is_some_and(|current| current.0 == conversation && current.1 == token)
        {
            self.visible = None;
        }
    }
    fn set_app_active(&mut self, active: bool) {
        self.app_active = active;
        if active {
            self.clear_visible();
        }
    }
    fn clear_visible(&mut self) {
        let Some((conversation, _)) = self.visible.as_ref() else {
            return;
        };
        if self.channel.as_deref() == Some(conversation.as_str()) {
            self.channel_count = 0;
        } else if let Some(uid) = conversation.strip_prefix("client:") {
            self.private_counts.remove(uid);
        }
    }
    fn snapshot(&self) -> Value {
        json!({"serverId":self.server,"channel":self.channel,"channelCount":self.channel_count,"privateCounts":self.private_counts})
    }
}

#[derive(Default)]
struct Output {
    connection_id: Option<String>,
    snapshot: Value,
    network_quality: Option<Value>,
    chat_snapshot: Option<Value>,
    chat_epoch: u64,
    receive_audio: Value,
    speech: Value,
    profile: Value,
    channel_files: Value,
    file_cache: Value,
    unread: Value,
    events: VecDeque<Value>,
    notifier: Option<(extern "C" fn(usize), usize)>,
}
impl Output {
    fn notify(&self) {
        if let Some((callback, context)) = self.notifier {
            callback(context);
        }
    }
    fn set_snapshot(&mut self, mut value: Value) {
        value["connectionId"] = json!(self.connection_id);
        value["networkQuality"] = self.network_quality.clone().unwrap_or(Value::Null);
        if self.snapshot != value {
            self.snapshot = value;
            self.notify();
        }
    }
    fn set_network_quality(&mut self, quality: &network_quality::Quality) {
        if self.snapshot["status"] != "connected" {
            return;
        }
        let value = serde_json::to_value(quality).expect("finite network statistics");
        if self.network_quality.as_ref() != Some(&value) {
            self.snapshot["networkQuality"] = value.clone();
            self.network_quality = Some(value);
            self.notify();
        }
    }
    #[cfg(test)]
    fn set_chats(&mut self, value: Value) {
        self.chat_snapshot = Some(value);
        self.notify();
    }
    fn set_unread(&mut self, value: Value) {
        if self.unread != value {
            self.unread = value;
            self.notify();
        }
    }
    fn event(&mut self, mut event: Value) {
        let kind = event["type"].as_str().unwrap_or_default();
        let code = event["code"].as_str().unwrap_or_default();
        let global = event["scope"] == "global" || matches!(kind, "identity" | "audio_processing" | "avatar_local" | "avatar_cleanup_failed" | "file_cache_result")
            || (kind == "error" && (code.starts_with("receive_audio_read") || code.starts_with("receive_audio_save")
                || code.starts_with("audio_processing") || code.starts_with("avatar_local") || code == "profile_read_failed"));
        if !global && event.get("connectionId").is_none() {
            event["connectionId"] = json!(self.connection_id);
        }
        if self.events.len() >= 64 {
            self.events.pop_front();
        }
        self.events.push_back(event);
        self.notify();
    }
    fn status(&mut self, status: &str) {
        self.network_quality = None;
        if self.speech.is_object() {
            self.speech["available"] = json!(false);
            self.speech["transmitting"] = json!(false);
        }
        self.set_snapshot(json!({"status":status,"channels":[],"clients":[]}));
    }
}

pub struct Bridge {
    worker: Option<std::thread::JoinHandle<()>>,
    tx: mpsc::Sender<Command>,
    pcm: mpsc::Sender<(u64, Vec<i16>)>,
    capture_epoch: Arc<AtomicU64>,
    output: Arc<Mutex<Output>>,
    avatar_store: Arc<avatar::Store>,
    playback: Arc<playback::Playback>,
}

fn report(out: &Arc<Mutex<Output>>, error: impl std::fmt::Display) {
    report_code(out, "core_error", Some(error.to_string()));
}
fn report_code(out: &Arc<Mutex<Output>>, code: &str, detail: Option<String>) {
    out.lock()
        .unwrap()
        .event(json!({"type":"error","code":code,"detail":detail}));
}
fn report_audio_muted(out: &Arc<Mutex<Output>>, code: &str, detail: Option<String>) {
    out.lock().unwrap().event(json!({
        "type":"audio_muted","code":code,"detail":detail
    }));
}
fn hex(value: &str) -> String {
    value
        .as_bytes()
        .iter()
        .map(|byte| format!("{byte:02x}"))
        .collect()
}
fn timestamp() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
fn received_id() -> String {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    format!(
        "received-{nanos}-{}",
        MESSAGE_SEQUENCE.fetch_add(1, Ordering::Relaxed)
    )
}
fn server_id(con: &Connection) -> Option<String> {
    con.get_state()
        .ok()
        .map(|state| state.server.public_key.get_uid())
}
fn current_channel_conversation(con: &Connection) -> Option<String> {
    let state = con.get_state().ok()?;
    let me = state.clients.get(&state.own_client)?;
    let channel = state.channels.get(&me.channel)?;
    Some(format!("channel:{}", channel_key(channel)))
}
fn channel_key(channel: &tsclientlib::data::Channel) -> String {
    channel
        .guid
        .clone()
        .unwrap_or_else(|| format!("id:{}", channel.id.0))
}
fn avatar_path(root: &Path, server: &str, uid: &str, hash: &str) -> PathBuf {
    root.join("media")
        .join(hex(server))
        .join("avatar")
        .join(format!("{}_{}.img", hex(uid), hex(hash)))
}
fn server_icon_path(root: &Path, server: &str, icon: u32) -> PathBuf {
    // Channel and group icons are the same TeamSpeak /icon_{id} resource.
    // Keep the existing cache directory so downloaded channel icons remain reusable.
    root.join("media")
        .join(hex(server))
        .join("channel_icon")
        .join(format!("{icon}.img"))
}
fn badge_icon_path(root: &Path, badge: &BadgeMetadata) -> PathBuf {
    root.join("media")
        .join("badge")
        .join(format!("{}_{}_64.png", badge.id, hex(&badge.filename)))
}
fn client_badges(value: &str) -> Vec<&'static BadgeMetadata> {
    let Some(ids) = value.split(':').find_map(|field| {
        let (key, value) = field.split_once('=')?;
        key.eq_ignore_ascii_case("badges").then_some(value)
    }) else {
        return Vec::new();
    };
    let mut seen = HashSet::new();
    ids.split(',')
        .filter_map(|id| {
            let id = id.trim();
            known_badges()
                .iter()
                .find(|badge| badge.id.eq_ignore_ascii_case(id))
        })
        .filter(|badge| seen.insert(badge.id.as_str()))
        .collect()
}
fn client_server_group_icons<'a>(
    client: &tsclientlib::data::Client,
    state: &'a tsclientlib::data::Connection,
) -> Vec<&'a tsclientlib::data::ServerGroup> {
    let mut groups: Vec<_> = client
        .server_groups
        .iter()
        .filter_map(|id| state.server_groups.get(id))
        .filter(|group| group.icon.0 != 0)
        .collect();
    groups.sort_by_key(|group| (group.sort_id, group.id.0));
    groups
}
fn builtin_group_icon(icon: u32) -> bool {
    matches!(icon, 100 | 300)
}
fn group_icon_snapshot(
    id: u64,
    name: &str,
    icon: u32,
    storage: Option<&Path>,
    server: &str,
) -> Value {
    let icon_path = storage.and_then(|root| {
        (!builtin_group_icon(icon))
            .then(|| server_icon_path(root, server, icon))
            .and_then(path_if_cached)
    });
    json!({"id":id,"name":name,"iconId":icon,"iconPath":icon_path})
}
fn chat_path(root: &Path, server: &str) -> PathBuf {
    root.join("chat").join(format!("{}.json", hex(server)))
}
fn path_if_cached(path: PathBuf) -> Option<String> {
    path.is_file().then(|| path.to_string_lossy().into_owned())
}
fn voice_encryption(mode: CodecEncryptionMode, is_unencrypted: Option<bool>) -> bool {
    match mode {
        CodecEncryptionMode::ForcedOn => true,
        CodecEncryptionMode::ForcedOff => false,
        // Send plaintext only when the channel explicitly allows it.
        CodecEncryptionMode::PerChannel => is_unencrypted != Some(true),
    }
}
fn audio_send_allowed(
    input_muted: bool,
    output_muted: bool,
    subscribed: bool,
    can_send: bool,
    joining: bool,
) -> bool {
    !input_muted && !output_muted && subscribed && can_send && !joining
}
fn send_voice(
    con: &mut Connection,
    codec: CodecType,
    data: &[u8],
) -> Result<(), Box<dyn std::error::Error>> {
    // Read the server-confirmed state for every packet, including the end marker.
    let state = con.get_state()?;
    let is_unencrypted = state
        .clients
        .get(&state.own_client)
        .and_then(|me| state.channels.get(&me.channel))
        .and_then(|channel| channel.is_unencrypted);
    let encrypt = voice_encryption(state.server.codec_encryption_mode, is_unencrypted);
    con.get_tsproto_client_mut()?
        .params
        .as_mut()
        .ok_or(tsclientlib::Error::InitserverParamsMissing)?
        .voice_encryption = encrypt;
    con.send_audio(OutAudio::new(&AudioData::C2S { id: 0, codec, data }))?;
    Ok(())
}

// Display grouping must not feed back into protocol fields or local audio intent.
#[derive(Debug, PartialEq)]
struct UserStatus {
    muted: bool,
    deafened: bool,
    away: bool,
}
impl UserStatus {
    fn new(
        input_muted: bool,
        output_muted: bool,
        output_only_muted: bool,
        away_message: Option<&str>,
    ) -> Self {
        Self {
            muted: input_muted,
            deafened: output_muted || output_only_muted,
            away: away_message.is_some(),
        }
    }
}

fn snapshot(
    con: &Connection,
    out: &Arc<Mutex<Output>>,
    audio: &AudioHandler,
    sending: bool,
    storage: Option<&Path>,
    receive: &receive_audio::Session,
) {
    let Ok(state) = con.get_state() else { return };
    // initserver arrives before our client and channel lists. Keep connecting
    // (or reconnecting) until there is an actual channel to display.
    if !state
        .clients
        .get(&state.own_client)
        .is_some_and(|me| state.channels.contains_key(&me.channel))
    {
        return;
    }
    let server_id = state.server.public_key.get_uid();
    let channels: Vec<_> = state
        .channels
        .values()
        .map(|channel| {
            let icon = channel.icon.map(|icon| icon.0).filter(|icon| *icon != 0);
            let icon_path = storage.and_then(|root| {
                icon.and_then(|icon| path_if_cached(server_icon_path(root, &server_id, icon)))
            });
            json!({
                "id":channel.id.0,"parent":channel.parent.0,"order":channel.order.0,
                "name":channel.name,"password":channel.has_password.unwrap_or(false),
                "permanent":channel.channel_type == ChannelType::Permanent,
                "key":channel_key(channel),"icon":icon,"iconPath":icon_path
            })
        })
        .collect();
    let clients: Vec<_> = state
        .clients
        .values()
        .map(|client| {
            let uid = client.uid.as_ref().map(|uid| format!("{}", uid.as_ref()));
            let avatar_path = storage.and_then(|root| {
                uid.as_deref().and_then(|uid| {
                    if client.avatar_hash.is_empty() {
                        None
                    } else {
                        path_if_cached(avatar_path(root, &server_id, uid, &client.avatar_hash))
                    }
                })
            });
            let badges: Vec<_> = client_badges(&client.badges)
                .into_iter()
                .map(|badge| {
                    let icon_path =
                        storage.and_then(|root| path_if_cached(badge_icon_path(root, badge)));
                    json!({
                        "id":&badge.id,"name":&badge.name,"description":&badge.description,
                        "filename":&badge.filename,"iconPath":icon_path
                    })
                })
                .collect();
            let server_group_icons: Vec<_> = client_server_group_icons(client, state)
                .into_iter()
                .map(|group| group_icon_snapshot(group.id.0, &group.name, group.icon.0, storage, &server_id))
                .collect();
            let channel_group_icon = state.channel_groups.get(&client.channel_group)
                .filter(|group| group.icon.0 != 0)
                .map(|group| group_icon_snapshot(group.id.0, &group.name, group.icon.0, storage, &server_id));
            let (volume, volume_token) = receive.fields(client.id);
            let status = UserStatus::new(
                client.input_muted,
                client.output_muted,
                client.output_only_muted,
                client.away_message.as_deref(),
            );
            json!({
                "volumePercent":volume,"volumeToken":volume_token,
                "id":client.id.0,"channel":client.channel.0,"uid":uid,"name":client.name,
                "avatarHash":client.avatar_hash,"avatarPath":avatar_path,
                "badges":badges,"serverGroupIcons":server_group_icons,"channelGroupIcon":channel_group_icon,
                "muted":status.muted,"deafened":status.deafened,"away":status.away,
                "speaking":if client.id == state.own_client { sending } else { audio.get_queues().contains_key(&client.id) }
            })
        })
        .collect();
    out.lock().unwrap().set_snapshot(json!({
        "status":"connected","server":state.server.name,"serverId":server_id,
        "ownClient":state.own_client.0,"channels":channels,"clients":clients,
        "canSend":con.can_send_audio()
    }));
}

fn image_data_valid(data: &[u8]) -> bool {
    data.starts_with(b"\x89PNG\r\n\x1a\n")
        || data.starts_with(b"\xff\xd8\xff")
        || data.starts_with(b"GIF87a")
        || data.starts_with(b"GIF89a")
        || data.starts_with(b"BM")
        || data.starts_with(b"II*\0")
        || data.starts_with(b"MM\0*")
        || (data.len() >= 12 && &data[..4] == b"RIFF" && &data[8..12] == b"WEBP")
}
fn atomic_write(path: &Path, data: &[u8]) -> std::io::Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)?;
    }
    let temporary = path.with_extension("tmp");
    fs::write(&temporary, data)?;
    fs::rename(temporary, path)
}
fn load_chat(root: &Path, server: &str) -> Result<ChatStore, String> {
    let path = chat_path(root, server);
    match fs::read(&path) {
        Ok(data) => serde_json::from_slice(&data)
            .map_err(|error| format!("{}: {error}", path.to_string_lossy())),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(ChatStore::default()),
        Err(error) => Err(error.to_string()),
    }
}
struct PersistJob {
    path: PathBuf,
    data: Vec<u8>,
    error_code: &'static str,
}
fn chat_snapshot(store: &ChatStore, storage: Option<&Path>, server: Option<&str>) -> Value {
    let mut avatars = HashMap::new();
    Value::Array(
        store
            .messages
            .iter()
            .map(|message| {
                let mut value = serde_json::to_value(message).unwrap_or_default();
                let path = storage.and_then(|root| {
                    server.and_then(|server| {
                        if message.sender_uid.is_empty() || message.sender_avatar_hash.is_empty() {
                            None
                        } else {
                            avatars.entry((&message.sender_uid, &message.sender_avatar_hash))
                                .or_insert_with(|| path_if_cached(avatar_path(root, server, &message.sender_uid, &message.sender_avatar_hash))).clone()
                        }
                    })
                });
                if let Some(object) = value.as_object_mut() {
                    object.insert("avatarPath".into(), json!(path));
                }
                value
            })
            .collect(),
    )
}
#[derive(Clone)]
struct MediaRequest {
    key: String,
    remote: String,
    local: PathBuf,
}
struct BadgeRequest {
    key: String,
    url: String,
    local: PathBuf,
}
fn avatar_remote(uid: &tsclientlib::Uid) -> String {
    format!("/avatar_{}", uid.as_avatar())
}
fn desired_media(con: &Connection, storage: &Path) -> Vec<MediaRequest> {
    let Ok(state) = con.get_state() else {
        return Vec::new();
    };
    let server = state.server.public_key.get_uid();
    let mut result = Vec::new();
    for client in state.clients.values() {
        let Some(uid) = client.uid.as_ref() else {
            continue;
        };
        if client.avatar_hash.is_empty() {
            continue;
        }
        let uid_text = format!("{}", uid.as_ref());
        result.push(MediaRequest {
            key: format!("{server}:avatar:{uid_text}:{}", client.avatar_hash),
            remote: avatar_remote(uid),
            local: avatar_path(storage, &server, &uid_text, &client.avatar_hash),
        });
    }
    let mut seen_icons = HashSet::new();
    let mut add_icon = |icon: u32| {
        if icon != 0 && seen_icons.insert(icon) {
            result.push(MediaRequest {
                key: format!("{server}:icon:{icon}"),
                remote: format!("/icon_{icon}"),
                local: server_icon_path(storage, &server, icon),
            });
        }
    };
    for client in state.clients.values() {
        for group in client_server_group_icons(client, state) {
            if !builtin_group_icon(group.icon.0) {
                add_icon(group.icon.0);
            }
        }
        if let Some(group) = state.channel_groups.get(&client.channel_group) {
            if !builtin_group_icon(group.icon.0) {
                add_icon(group.icon.0);
            }
        }
    }
    for channel in state.channels.values() {
        if let Some(icon) = channel.icon {
            add_icon(icon.0);
        }
    }
    result
}
fn schedule_media(con: &mut Connection, storage: Option<&Path>, downloads: &mut media::Downloads) {
    let Some(storage) = storage else { return };
    for request in desired_media(con, storage) {
        if !downloads.wants(&request.key, &request.local) { continue; }
        match con.download_file(ChannelId(0), &request.remote, None, None) {
            Ok(handle) => downloads.wait(handle, request.key, request.local),
            Err(_) => downloads.reject(request.key),
        }
    }
}
fn desired_badges(con: &Connection, storage: &Path) -> Vec<BadgeRequest> {
    let Ok(state) = con.get_state() else {
        return Vec::new();
    };
    let mut seen = HashSet::new();
    state
        .clients
        .values()
        .flat_map(|client| client_badges(&client.badges))
        .filter(|badge| seen.insert(badge.id.as_str()))
        .map(|badge| BadgeRequest {
            key: format!("badge:{}:{}:64", badge.id, badge.filename),
            url: format!(
                "https://badges-content.teamspeak.com/{}/{}_64.png",
                badge.id, badge.filename
            ),
            local: badge_icon_path(storage, badge),
        })
        .collect()
}
fn schedule_badges(con: &Connection, storage: Option<&Path>, downloads: &mut media::Downloads, tx: &mpsc::UnboundedSender<media::Completion>) {
    let Some(storage) = storage else { return };
    for request in desired_badges(con, storage) {
        if downloads.wants(&request.key, &request.local) {
            downloads.badge(request.key, request.local, request.url, tx);
        }
    }
}

enum PendingOperation {
    Chat(String),
    FilesList,
    Avatar(avatar::Token),
    AvatarInfo(avatar::Token),
    AvatarDelete(avatar::Token),
    AvatarDrain(avatar::Token),
    AvatarStop(avatar::Token),
    Join,
    ProfileRename(u64),
    Other(&'static str),
}

fn apply_nickname(con: &mut Connection, profile: &profile::Profile, application: &mut profile::Application,
    operations: &mut HashMap<MessageHandle, PendingOperation>, out: &Arc<Mutex<Output>>) {
    let actual = con.get_state().ok().and_then(|state| state.clients.get(&state.own_client)).map(|me| me.name.as_str());
    let Some(token) = application.plan(profile, actual) else { return };
    let update = con.get_state().unwrap().client_update().set_name(&profile.nickname);
    match update.send_with_result(con) {
        Ok(handle) => { operations.insert(handle, PendingOperation::ProfileRename(token)); },
        Err(error) => application.result(token, profile, Err(error.to_string()), out),
    }
}

fn is_joining(operations: &HashMap<MessageHandle, PendingOperation>) -> bool {
    operations.values().any(|op| matches!(op, PendingOperation::Join))
}

fn outgoing_message(
    con: &Connection,
    request_id: String,
    target: MessageTarget,
    expected_channel: Option<u64>,
    expected_uid: Option<&str>,
    text: String,
) -> Result<ChatMessage, &'static str> {
    let state = con.get_state().map_err(|_| "client_state_unavailable")?;
    let own = state
        .clients
        .get(&state.own_client)
        .ok_or("client_state_unavailable")?;
    let sender_uid = own
        .uid
        .as_ref()
        .map(|uid| format!("{}", uid.as_ref()))
        .unwrap_or_default();
    let (kind, target_id, target_name, conversation) = match target {
        MessageTarget::Channel => {
            let expected = expected_channel.ok_or("channel_unavailable")?;
            if own.channel.0 != expected {
                return Err("message_wrong_channel");
            }
            let channel = state
                .channels
                .get(&own.channel)
                .ok_or("channel_unavailable")?;
            let key = channel_key(channel);
            (
                ChatKind::Channel,
                key.clone(),
                channel.name.clone(),
                format!("channel:{key}"),
            )
        }
        MessageTarget::Client(client_id) => {
            let client = state.clients.get(&client_id).ok_or("user_offline")?;
            let uid = client
                .uid
                .as_ref()
                .map(|uid| format!("{}", uid.as_ref()))
                .ok_or("user_identity_unavailable")?;
            if expected_uid != Some(uid.as_str()) {
                return Err("user_connection_changed");
            }
            (
                ChatKind::Private,
                uid.clone(),
                client.name.clone(),
                format!("client:{uid}"),
            )
        }
        _ => return Err("unsupported_message_target"),
    };
    Ok(ChatMessage {
        id: request_id,
        conversation,
        kind,
        target_id,
        target_name,
        sender_uid,
        sender_name: own.name.clone(),
        sender_avatar_hash: own.avatar_hash.clone(),
        own: true,
        text,
        timestamp: timestamp(),
        status: ChatStatus::Pending,
        error: None,
    })
}
fn rejected_message(
    con: &Connection,
    request_id: String,
    target: MessageTarget,
    expected_channel: Option<u64>,
    expected_uid: Option<&str>,
    text: String,
    error: String,
) -> ChatMessage {
    let state = con.get_state().ok();
    let own = state.and_then(|state| state.clients.get(&state.own_client));
    let sender_uid = own
        .and_then(|client| client.uid.as_ref())
        .map(|uid| format!("{}", uid.as_ref()))
        .unwrap_or_default();
    let (kind, target_id, target_name, conversation) = match target {
        MessageTarget::Channel => {
            let id = expected_channel.unwrap_or_default();
            let channel = state.and_then(|state| state.channels.get(&ChannelId(id)));
            let key = channel
                .map(channel_key)
                .unwrap_or_else(|| format!("id:{id}"));
            (
                ChatKind::Channel,
                key.clone(),
                channel
                    .map(|channel| channel.name.clone())
                    .unwrap_or_else(|| format!("#{id}")),
                format!("channel:{key}"),
            )
        }
        MessageTarget::Client(_) => {
            let uid = expected_uid.unwrap_or_default().to_owned();
            let name = state
                .and_then(|state| {
                    state.clients.values().find(|client| {
                        client
                            .uid
                            .as_ref()
                            .is_some_and(|value| format!("{}", value.as_ref()) == uid)
                    })
                })
                .map(|client| client.name.clone())
                .unwrap_or_else(|| uid.clone());
            (
                ChatKind::Private,
                uid.clone(),
                name,
                format!("client:{uid}"),
            )
        }
        _ => unreachable!(),
    };
    ChatMessage {
        id: request_id,
        conversation,
        kind,
        target_id,
        target_name,
        sender_uid,
        sender_name: own.map(|client| client.name.clone()).unwrap_or_default(),
        sender_avatar_hash: own
            .map(|client| client.avatar_hash.clone())
            .unwrap_or_default(),
        own: true,
        text,
        timestamp: timestamp(),
        status: ChatStatus::Failed,
        error: Some(error),
    }
}
fn received_messages(events: Vec<Event>, con: &Connection) -> Vec<ChatMessage> {
    let Ok(state) = con.get_state() else {
        return Vec::new();
    };
    let own_id = state.own_client;
    let own_channel = state.clients.get(&own_id).map(|client| client.channel);
    let mut result = Vec::new();
    for event in events {
        let Event::Message {
            target,
            invoker,
            message,
        } = event
        else {
            continue;
        };
        if invoker.id == own_id {
            continue;
        }
        let sender = state.clients.get(&invoker.id);
        let sender_uid = invoker
            .uid
            .as_ref()
            .or_else(|| sender.and_then(|client| client.uid.as_ref()))
            .map(|uid| format!("{}", uid.as_ref()))
            .unwrap_or_default();
        let avatar_hash = sender
            .map(|client| client.avatar_hash.clone())
            .unwrap_or_default();
        let (kind, target_id, target_name, conversation) = match target {
            MessageTarget::Channel => {
                let Some(channel) = own_channel.and_then(|id| state.channels.get(&id)) else {
                    continue;
                };
                let key = channel_key(channel);
                (
                    ChatKind::Channel,
                    key.clone(),
                    channel.name.clone(),
                    format!("channel:{key}"),
                )
            }
            MessageTarget::Client(_) => {
                if sender_uid.is_empty() {
                    continue;
                }
                (
                    ChatKind::Private,
                    sender_uid.clone(),
                    invoker.name.clone(),
                    format!("client:{sender_uid}"),
                )
            }
            MessageTarget::Server | MessageTarget::Poke(_) => continue,
        };
        result.push(ChatMessage {
            id: received_id(),
            conversation,
            kind,
            target_id,
            target_name,
            sender_uid,
            sender_name: invoker.name,
            sender_avatar_hash: avatar_hash,
            own: false,
            text: message,
            timestamp: timestamp(),
            status: ChatStatus::Received,
            error: None,
        });
    }
    result
}

#[derive(Default)]
struct AudioProcessingState {
    requested: audio_models::Selection,
    applied: audio_models::Selection,
    generation: u64,
    active_generation: u64,
    request_id: u64,
    switching: bool,
}
impl AudioProcessingState {
    fn publish(&self, out: &Arc<Mutex<Output>>, status: &str, detail: Option<String>) {
        out.lock().unwrap().event(json!({"type":"audio_processing","status":status,"requested":self.requested,"applied":self.applied,"generation":self.generation,"requestId":self.request_id,"detail":detail}));
    }
    fn request(&mut self, processor: &audio_processing::Processor, out: &Arc<Mutex<Output>>) {
        self.generation = processor.request(self.requested);
        self.switching = true;
        self.publish(out, "switching", None);
    }
    fn completed(&mut self, event: audio_processing::Event, out: &Arc<Mutex<Output>>) {
        match event {
            audio_processing::Event::Applied {
                generation,
                selection,
                elapsed_ms,
            } => {
                if generation == self.generation { self.switching = false; }
                self.active_generation = generation;
                self.applied = selection;
                self.publish(
                    out,
                    if generation == self.generation {
                        "ready"
                    } else {
                        "switching"
                    },
                    None,
                );
                #[cfg(debug_assertions)]
                diagnostic!("Audio processing applied {selection:?} in {elapsed_ms}ms");
                let _ = elapsed_ms;
            }
            audio_processing::Event::Failed {
                generation,
                selection,
                error,
            } if generation == self.generation => {
                self.switching = false;
                self.applied = selection;
                self.requested = selection;
                self.publish(out, "failed", Some(error));
            }
            _ => {}
        }
    }
}

fn flush_capture(processor: &mut audio_processing::Processor, epoch: &AtomicU64) {
    epoch.fetch_add(1, Ordering::AcqRel);
    processor.invalidate();
}

fn invalidate_capture(processor: &mut audio_processing::Processor, epoch: &AtomicU64, speech: &mut speech::State) {
    speech.invalidate();
    flush_capture(processor, epoch);
}

async fn session(
    first: Command,
    playback: &Arc<playback::Playback>,
    refill: &tokio::net::UnixDatagram,
    rx: &mut mpsc::Receiver<Command>,
    pcm: &mut mpsc::Receiver<(u64, Vec<i16>)>,
    capture_epoch: &AtomicU64,
    out: &Arc<Mutex<Output>>,
    storage: Option<PathBuf>,
    persist_tx: &std_mpsc::Sender<PersistJob>,
    chat_worker: &chat::Worker,
    downloads: &mut media::Downloads,
    app_active: &mut bool,
    processing: &mut AudioProcessingState,
    processor: &mut audio_processing::Processor,
    receive_prefs: &mut receive_audio::Preferences,
    speech: &mut speech::State,
    profile: &mut profile::Profile,
    avatar_store: &Arc<avatar::Store>,
    files: &mut channel_files::Files,
    files_tx: &mpsc::UnboundedSender<channel_files::Update>,
    files_rx: &mut mpsc::UnboundedReceiver<channel_files::Update>,
) -> Result<bool, Box<dyn std::error::Error>> {
    let Command::Connect {
        connection_id,
        address,
        password,
        identity,
    } = first
    else {
        return Ok(false);
    };
    let connection_started = Instant::now();
    diagnostic!("Connection startup phase=worker_begin");
    {
        let mut output = out.lock().unwrap();
        output.connection_id = connection_id;
        output.status("connecting");
    }
    let mut chats = chat_worker.session();
    let mut unread = UnreadState {
        app_active: *app_active,
        ..Default::default()
    };
    out.lock().unwrap().set_unread(unread.snapshot());
    while pcm.try_recv().is_ok() {}
    let identity = match identity {
        Some(value) => serde_json::from_value::<Identity>(value)?,
        None => Identity::create(),
    };
    diagnostic!(
        "Connection startup phase=identity_ready total_ms={}",
        connection_started.elapsed().as_millis()
    );
    out.lock()
        .unwrap()
        .event(json!({"type":"identity","value":identity}));
    let mut con = Connection::build(address)
        .name(profile.nickname.clone())
        .password(password)
        .identity(identity)
        .input_muted(true)
        .output_muted(false)
        .input_hardware_enabled(true)
        .output_hardware_enabled(true)
        .connect()?;
    diagnostic!(
        "Connection startup phase=handshake_begin total_ms={}",
        connection_started.elapsed().as_millis()
    );
    let mut audio = AudioHandler::new();
    let mut receive = receive_audio::Session::default();
    let mut mix_limiter = receive_audio::Limiter::default();
    let mut receive_traces = HashMap::<tsclientlib::ClientId, playback::ReceiveTrace>::new();
    let audio_trace_start = Instant::now();
    invalidate_capture(processor, capture_epoch, speech);
    let mut voice = voice::VoiceSender::new()?;
    let mut voice_channel = None;
    let mut input_muted = true;
    let mut output_muted = false;
    let mut subscribed = false;
    let mut first_connection = true;
    let mut current_server: Option<String> = None;
    let mut network = network_quality::History::new(Instant::now());
    #[cfg(feature = "network-diagnostics")]
    let mut network_trace: Option<network_diagnostics::Diagnostics> = None;
    let mut operations = HashMap::<MessageHandle, PendingOperation>::new();
    let mut nickname_application = profile::Application::default();
    let mut avatar = avatar::AvatarSync::default();
    avatar.ready();
    avatar.observe(avatar_store, out);
    let (avatar_tx, mut avatar_rx) = mpsc::unbounded_channel::<avatar::Completion>();
    let (media_tx, mut media_rx) = mpsc::unbounded_channel::<media::Completion>();
    let mut media_retry = Instant::now();
    let mut clock = tokio::time::interval(Duration::from_millis(20));
    clock.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let deadline = tokio::time::sleep(Duration::from_secs(30));
    tokio::pin!(deadline);
    let mut voice_timer: Option<std::pin::Pin<Box<tokio::time::Sleep>>> = None;
    let mut avatar_timer: Option<std::pin::Pin<Box<tokio::time::Sleep>>> = None;
    let mut playback_revision = playback.revision.load(Ordering::Acquire);
    let mut refill_byte = [0u8];
    let mut armed_deadline = None;
    let mut armed_avatar_deadline = None;
    let mut playback_log_at = Instant::now();
    let mut playback_counts = playback.diagnostics();
    let mut last_refill = Instant::now();
    let mut max_refill_gap_ms = 0u128;
    let mut max_refill_work_us = 0u128;
    let mut received_packets = 0u64;
    let mut rejected_packets = 0u64;
    let mut last_packet_error = String::new();
    let mut ended_queues = 0u64;
    playback.clear();
    playback.request();
    loop {
        let available = *app_active && speech.capture_ready && !processing.switching
            && audio_send_allowed(input_muted, output_muted, subscribed, con.can_send_audio(), is_joining(&operations))
            && matches!(voice_channel, Some((_, Some(tsclientlib::Codec::OpusVoice | tsclientlib::Codec::OpusMusic))));
        if speech.available(available) && speech.mode == speech::Mode::PushToTalk {
            flush_capture(processor, capture_epoch);
            let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
            snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
        }
        speech.publish(voice.speaking(), out);
        let voice_deadline = voice.deadline();
        if voice_deadline != armed_deadline {
            match voice_deadline {
                Some(at) => match voice_timer.as_mut() {
                    Some(timer) => timer.as_mut().reset(at.into()),
                    None => voice_timer = Some(Box::pin(tokio::time::sleep_until(at.into()))),
                },
                None => voice_timer = None, // Drop unregisters the one-shot timer immediately.
            }
            armed_deadline = voice_deadline;
        }
        let avatar_deadline = avatar.deadline();
        if avatar_deadline != armed_avatar_deadline {
            match avatar_deadline {
                Some(at) => match avatar_timer.as_mut() {
                    Some(timer) => timer.as_mut().reset(at),
                    None => avatar_timer = Some(Box::pin(tokio::time::sleep_until(at))),
                },
                None => avatar_timer = None,
            }
            armed_avatar_deadline = avatar_deadline;
        }
        tokio::select! {
            _ = async {
                match avatar_timer.as_mut() {
                    Some(timer) => timer.as_mut().await,
                    None => std::future::pending::<()>().await,
                }
            } => {
                avatar.observe(avatar_store, out);
                avatar.expire(out);
                if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
            },
            _ = async {
                match voice_timer.as_mut() {
                    Some(timer) => timer.as_mut().await,
                    None => std::future::pending::<()>().await,
                }
            } => {
                let speaking = voice.speaking();
                let result = voice.expire(Instant::now(), |codec, data| send_voice(&mut con, codec, data));
                if speaking != voice.speaking() { snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive); }
                result?;
            },
            Ok(_) = refill.recv(&mut refill_byte) => {
                playback.acknowledged();
                let revision = playback.revision.load(Ordering::Acquire);
                if revision != playback_revision {
                    playback_revision = revision;
                    last_refill = Instant::now();
                    audio.reset(); receive_traces.clear(); playback.clear();
                    snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive);
                }
                if subscribed && !output_muted && playback.active.load(Ordering::Acquire) {
                    let started = Instant::now();
                    max_refill_gap_ms = max_refill_gap_ms.max(started.duration_since(last_refill).as_millis());
                    last_refill = started;
                    let ended = playback.refill_with_proc(&mut audio, |id, pcm| receive.process(id, pcm, receive_prefs.agc), |pcm| mix_limiter.process(pcm));
                    max_refill_work_us = max_refill_work_us.max(started.elapsed().as_micros());
                    ended_queues += u64::from(!ended.is_empty());
                    playback.prepared(revision);
                    for client in &ended {
                        if let Some(trace) = receive_traces.remove(client) {
                            // This observes accepted markers, not the private decoder's exact
                            // removal reason. A marker may still be behind a missing packet.
                            diagnostic!("Playback receive_end at_ms={} client={} end_marker_observed={} packets={} end_markers={} duration_ms={} last_packet_age_ms={} max_packet_gap_ms={} sequence_skips={} reordered_or_duplicate={} rejected={}",
                                audio_trace_start.elapsed().as_millis(), client.0, trace.end_markers > 0,
                                trace.packets, trace.end_markers, trace.started.elapsed().as_millis(),
                                trace.last_packet.elapsed().as_millis(), trace.max_gap_ms, trace.sequence_skips,
                                trace.reordered_or_duplicate, trace.rejected);
                        }
                    }
                    if !ended.is_empty() { snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive); }
                }
            },
            _ = &mut deadline, if first_connection => return Err("Connection timed out after 30 seconds".into()),
            event = std::future::poll_fn(|cx| {
                let event = con.events().poll_next_unpin(cx);
                #[cfg(feature = "network-diagnostics")]
                if let Some(trace) = &network_trace { trace.observe(&con); }
                event
            }) => match event {
                Some(Ok(StreamItem::BookEvents(events))) => {
                    // Server-confirmed names stay in bookkeeping; reconnect intent stays global.
                    con.set_connect_name(profile.nickname.clone());
                    apply_nickname(&mut con, profile, &mut nickname_application, &mut operations, out);
                    #[cfg(feature = "network-diagnostics")]
                    if network_trace.is_none() { network_trace = network_diagnostics::Diagnostics::attach(&mut con); }
                    for event in &events {
                        if let Event::PropertyRemoved { id: tsclientlib::events::PropertyId::Client(id), .. } = event {
                            receive.remove(*id); audio.get_mut_queues().remove(id);
                        }
                    }
                    receive.sync(&con, receive_prefs, &mut audio);
                    for (id, queue) in audio.get_mut_queues() { queue.volume = receive.fields(*id).0 as f32 / 100.; }
                    files.observe(&con, out);
                    avatar.observe(avatar_store, out);
                    let channel = con.get_state().ok().and_then(|state| state.clients.get(&state.own_client).map(|me| (me.channel, state.channels.get(&me.channel).map(|channel| channel.codec))));
                    if channel != voice_channel {
                        audio.reset(); receive_traces.clear(); playback.clear(); playback.request();
                    }
                    if channel != voice_channel || !con.can_send_audio() {
                        invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                        while pcm.try_recv().is_ok() {}
                        voice_channel = channel;
                    }
                    if let Ok(state) = con.get_state() {
                        if events.iter().any(|event| matches!(event, Event::PropertyChanged { id: tsclientlib::events::PropertyId::ClientAvatarHash(client), .. } if *client == state.own_client)) {
                            if let Some(me) = state.clients.get(&state.own_client) {
                                avatar.server_hash_changed(&me.avatar_hash, out);
                            }
                        }
                    }
                    if !subscribed && con.get_state().is_ok() {
                        let handle = con
                            .get_state()?
                            .server
                            .set_subscribed(true)
                            .send_with_result(&mut con)?;
                        operations.insert(handle, PendingOperation::Other("channel_subscribe_failed"));
                        subscribed = true;
                    }
                    let initial_connection = first_connection && current_channel_conversation(&con).is_some();
                    if initial_connection {
                        diagnostic!("Connection startup phase=protocol_ready total_ms={}", connection_started.elapsed().as_millis());
                        first_connection = false;
                    }
                    let connected_server = server_id(&con);
                    if current_server != connected_server {
                        network = network_quality::History::new(Instant::now());
                        out.lock().unwrap().network_quality = None;
                        current_server = connected_server;
                        chats.select(storage.clone(), current_server.clone());
                    }
                    unread.sync(current_server.clone(), current_channel_conversation(&con));
                    let received = received_messages(events, &con);
                    if !received.is_empty() {
                        for message in &received { unread.record(message); }
                        chats.add(received);

                    }
                    out.lock().unwrap().set_unread(unread.snapshot());
                    snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive);
                    if initial_connection {
                        diagnostic!("Connection startup phase=snapshot_published total_ms={}", connection_started.elapsed().as_millis());
                    }
                    if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                    schedule_media(&mut con, storage.as_deref(), downloads);
                    schedule_badges(&con, storage.as_deref(), downloads, &media_tx);
                },
                Some(Ok(StreamItem::IdentityLevelIncreased)) => {
                    diagnostic!("Connection startup phase=identity_level_ready total_ms={}", connection_started.elapsed().as_millis());
                    out.lock().unwrap().event(json!({"type":"identity","value":con.get_options().get_identity()}));
                },
                Some(Ok(StreamItem::IdentityLevelIncreasing(level))) => {
                    diagnostic!("Connection startup phase=identity_level_increasing level={level} total_ms={}", connection_started.elapsed().as_millis());
                    if level > 24 { con.cancel_identity_level_increase(); return Err("Server requires identity level above 24; import a higher-level identity".into()); }
                },
                Some(Ok(StreamItem::AudioChange(change))) => {
                    match change {
                        tsclientlib::AudioEvent::CanSendAudio(false) => {
                            invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                            while pcm.try_recv().is_ok() {}
                        },
                        tsclientlib::AudioEvent::CanReceiveAudio(false) => { audio.reset(); receive_traces.clear(); playback.clear(); playback.request(); },
                        _ => {},
                    }
                    snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive);
                },
                Some(Ok(StreamItem::Audio(packet))) => {
                    if !output_muted && playback.active.load(Ordering::Acquire) {
                        let from = match packet.data().data() {
                            AudioData::S2C { from, .. } | AudioData::S2CWhisper { from, .. } => tsclientlib::ClientId(*from),
                            _ => continue,
                        };
                        received_packets += 1;
                        let sequence = packet.data().data().id();
                        let end_marker = packet.data().data().data().len() <= 1;
                        let now = Instant::now();
                        match audio.handle_packet(from, packet) {
                            Ok(started) => {
                                if !end_marker { receive.prepare_audio(from); }
                                if let Some(queue) = audio.get_mut_queues().get_mut(&from) { queue.volume = receive.fields(from).0 as f32 / 100.; }
                                if started.is_some() {
                                    receive_traces.insert(from, playback::ReceiveTrace::new(now));
                                    diagnostic!("Playback receive_start at_ms={} client={} sequence={}", audio_trace_start.elapsed().as_millis(), from.0, sequence);
                                }
                                if let Some(trace) = receive_traces.get_mut(&from) { trace.received(sequence, end_marker, now); }
                                if started.is_some() { snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive); }
                            },
                            Err(error) => {
                                rejected_packets += 1; last_packet_error = error.to_string();
                                if let Some(trace) = receive_traces.get_mut(&from) { trace.rejected += 1; }
                            },
                        }
                    }
                },
                Some(Ok(StreamItem::MessageResult(handle, result))) => match operations.remove(&handle) {
                    Some(PendingOperation::ProfileRename(token)) => {
                        nickname_application.result(token, profile, result.map_err(|error| error.to_string()), out);
                        apply_nickname(&mut con, profile, &mut nickname_application, &mut operations, out);
                    },
                    Some(PendingOperation::FilesList) => { files.list_result(handle, result, out); files.pump(&mut con, &mut operations); },
                    Some(PendingOperation::Chat(id)) => {
                        match result {
                            Ok(()) => chats.finish(&id, ChatStatus::Sent, None),
                            Err(_) => chats.finish(&id, ChatStatus::Failed, Some("message_send_failed".into())),
                        }

                    }
                    Some(PendingOperation::Join) => if let Err(error) = result { report_code(out, "join_channel_failed", Some(error.to_string())); },
                    Some(PendingOperation::Other(code)) => if let Err(error) = result {
                        report_code(out, code, Some(error.to_string()));
                    },
                    Some(PendingOperation::Avatar(generation)) => {
                        avatar.observe(avatar_store, out);
                        avatar.updated(generation, result.map_err(|error| format!("{:?} (0x{:04x}) missing_permission={:?}", error.error, error.error as u32, error.missing_permission)), out);
                        if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                    },
                    Some(PendingOperation::AvatarDrain(token)) => {
                        avatar.observe(avatar_store, out);
                        avatar.transfer_list_result(token, result.map_err(|e| format!("{:?} (0x{:04x}) missing_permission={:?}", e.error, e.error as u32, e.missing_permission)), &mut con, &mut operations, out);
                        if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                    },
                    Some(PendingOperation::AvatarStop(token)) => {
                        avatar.observe(avatar_store, out);
                        avatar.transfer_stopped(token, result.map_err(|e| format!("{:?} (0x{:04x}) missing_permission={:?}", e.error, e.error as u32, e.missing_permission)), out);
                        if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                    },
                    Some(PendingOperation::AvatarDelete(token)) => {
                        avatar.observe(avatar_store, out);
                        avatar.deleted(token, result, out);
                        if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                    },
                    Some(PendingOperation::AvatarInfo(token)) => {
                        avatar.observe(avatar_store, out);
                        avatar.info_result(token, result.map_err(|error| format!("{:?} (0x{:04x}) missing_permission={:?}", error.error, error.error as u32, error.missing_permission)), &mut con, out);
                        if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                    },
                    None => {},
                },
                Some(Ok(StreamItem::MessageEvent(msg))) => {
                    avatar.observe(avatar_store, out);
                    files.message(&msg, storage.as_deref(), out);
                    files.pump(&mut con, &mut operations);
                    avatar.info(&msg, &mut con, out);
                    if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                },
                Some(Ok(StreamItem::FileUpload(handle, transfer))) => {
                    if files.owns(handle) { files.upload(handle, transfer, &files_tx, out); continue; }
                    avatar.observe(avatar_store, out);
                    avatar.file_upload(handle, transfer, &avatar_tx, out);
                    if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                },
                Some(Ok(StreamItem::FileDownload(handle, download))) => {
                    if files.owns(handle) { files.download(handle, download, &files_tx, out); continue; }
                    avatar.observe(avatar_store, out);
                    let mut download = Some(download);
                    if avatar.file_download(handle, &mut download, &avatar_tx, out) {
                        if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                        continue;
                    }
                    let download = download.unwrap();
                    downloads.download(handle, download, &media_tx);
                },
                Some(Ok(StreamItem::FiletransferFailed(handle, error))) => {
                    if files.owns(handle) { files.failed(handle, error, out); continue; }
                    avatar.observe(avatar_store, out);
                    if avatar.file_failed(handle, error, out) {
                        avatar.finish_upload(&mut con, &mut operations, out);
                        if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                        continue;
                    }
                    downloads.failed(handle);
                    schedule_media(&mut con, storage.as_deref(), downloads);
                },
                Some(Ok(StreamItem::NetworkStatsUpdated)) => {
                    #[cfg(feature = "network-diagnostics")]
                    if let Some(trace) = &network_trace { trace.snapshot(&con); }
                    let stats = if current_channel_conversation(&con).is_some() { con.get_network_stats().ok() } else { None };
                    let quality = network.update(stats, Instant::now());
                    out.lock().unwrap().set_network_quality(quality);
                },
                Some(Ok(StreamItem::DisconnectedTemporarily(_))) => {
                    con.set_connect_name(profile.nickname.clone());
                    nickname_application.disconnected();
                    #[cfg(feature = "network-diagnostics")]
                    { network_trace = None; }
                    files.disconnected(out);
                    network = network_quality::History::new(Instant::now());
                    avatar.observe(avatar_store, out);
                    chats.fail_pending("message_unconfirmed_after_reconnect");
                    operations.clear();
                    if avatar.disconnected() {
                        out.lock().unwrap().event(json!({"type":"avatar_sync","status":"idle","detail":null}));
                    }
                    subscribed = false;
                    receive.clear();
                    for handle in downloads.cancel() { con.cancel_filetransfer(handle); }
                    audio.reset(); receive_traces.clear(); playback.clear();
                    invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|_, _| Ok(()));
                    voice_channel = None;
                    while pcm.try_recv().is_ok() {}
                    out.lock().unwrap().status("reconnecting");
                },
                Some(Err(error)) => return Err(error.into()),
                None => return Ok(false),
            },
            Some(completion) = media_rx.recv() => {
                if downloads.complete(completion) {
                    chats.refresh();
                    snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive);

                }
                schedule_media(&mut con, storage.as_deref(), downloads);
                schedule_badges(&con, storage.as_deref(), downloads, &media_tx);
            },
            Some(update) = files_rx.recv() => { files.update(update, out); files.pump(&mut con, &mut operations); },
            Some(completion) = avatar_rx.recv() => {
                avatar.observe(avatar_store, out);
                avatar.completed(completion, &mut con, &mut operations, out);
                if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
            },
            received = rx.recv() => match received {
                Some(Command::SetNickname { nickname, request_id }) => {
                    if profile.save(&nickname, request_id, out) {
                        con.set_connect_name(profile.nickname.clone());
                        nickname_application.changed();
                        apply_nickname(&mut con, profile, &mut nickname_application, &mut operations, out);
                    }
                },
                Some(Command::FileCacheInspect { request_id }) => { files.cache_command(request_id, false, storage.as_deref(), files_tx, out); },
                Some(Command::FileCacheClear { request_id }) => { files.cache_command(request_id, true, storage.as_deref(), files_tx, out); },
                Some(command @ (Command::FilesOpen { .. } | Command::FilesBack { .. } | Command::FilesList { .. } | Command::FilesSort { .. } | Command::FilesDownload { .. } | Command::FilesUpload { .. } | Command::FilesRetry { .. })) => {
                    files.command(command, &mut con, storage.as_deref(), &mut operations, out);
                },
                Some(Command::Disconnect) | None => {
                    for handle in downloads.cancel() { con.cancel_filetransfer(handle); }
                    avatar.disconnected();
                    invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                    playback.clear(); out.lock().unwrap().status("disconnected");
                    // Only an established connection can send clientdisconnect.
                    // Drop pending handshakes; stop draining on errors so a failed
                    // handshake's completed future is never polled again.
                    if con.get_state().is_ok() {
                        con.disconnect(DisconnectOptions::new())?;
                        let _ = tokio::time::timeout(Duration::from_secs(2), async { while let Some(Ok(_)) = con.events().next().await {} }).await;
                    }
                    return Ok(false);
                },
                Some(Command::Shutdown) => { for handle in downloads.cancel() { con.cancel_filetransfer(handle); } avatar.disconnected(); invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data)); playback.clear(); out.lock().unwrap().status("disconnected"); let _ = con.disconnect(DisconnectOptions::new()); return Ok(true); },
                #[cfg(test)]
                Some(Command::PanicSession) => panic!("injected session failure"),
                Some(Command::Connect { .. }) => report_code(out, "disconnect_before_connect", None),
                Some(Command::AvatarSelect { .. } | Command::AvatarSet { .. }) => unreachable!("avatar storage commands are handled by Bridge::send"),
                Some(Command::Configure { .. }) => report_code(out, "storage_change_while_connected", None),
                Some(Command::AvatarChanged) => {
                    avatar.observe(avatar_store, out);
                    if subscribed { avatar.check(&mut con, storage.as_deref(), &avatar_tx, &mut operations, out); }
                },
                Some(Command::SetChatVisible { server, conversation, token, visible }) => {
                    unread.set_visible(&server, &conversation, &token, visible);
                    out.lock().unwrap().set_unread(unread.snapshot());
                },
                Some(Command::SetAppActive { active }) => {
                    if active && !*app_active {
                        let quality = network.resume(Instant::now());
                        out.lock().unwrap().set_network_quality(quality);
                    }
                    if !active && speech.mode == speech::Mode::PushToTalk {
                        invalidate_capture(processor, capture_epoch, speech);
                        let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                        snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
                    }
                    *app_active = active;
                    if !active { receive_prefs.flush(storage.as_deref(), persist_tx, out, true); }
                    unread.set_app_active(active);
                    out.lock().unwrap().set_unread(unread.snapshot());
                },
                Some(Command::Join { channel, password }) => {
                    files.join_password(channel, password.clone());
                    if !subscribed { report_code(out, "connection_recovering", None); continue; }
                    let state = con.get_state()?;
                    if state.clients.get(&state.own_client).is_some_and(|client| client.channel.0 == channel) { continue; }
                    let Some(me) = state.clients.get(&state.own_client) else { report_code(out, "client_state_unavailable", None); continue; };
                    let request = me.client_move(ChannelId(channel)).set_password(&password).to_packet();
                    invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                    while pcm.try_recv().is_ok() {}
                    audio.reset(); receive_traces.clear(); playback.clear(); playback.request();
                    snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
                    let handle = request.send_with_result(&mut con)?;
                    operations.insert(handle, PendingOperation::Join);
                },
                Some(Command::Mute { input, output }) => {
                    if input || output { invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data)); }
                    if output_muted != output { audio.reset(); receive_traces.clear(); playback.clear(); playback.request(); }
                    input_muted = input; output_muted = output;
                    while pcm.try_recv().is_ok() {}
                    snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive);
                    if subscribed {
                        let update = con.get_state()?.client_update()
                            .set_input_muted(input)
                            .set_output_muted(output);
                        #[cfg(feature = "network-diagnostics")]
                        if let Some(trace) = &network_trace { trace.queued_mute(&con, input, output); }
                        match update.send_with_result(&mut con) {
                            Ok(handle) => { operations.insert(handle, PendingOperation::Other("update_audio_state_failed")); },
                            Err(error) => report_code(out, "update_audio_state_failed", Some(error.to_string())),
                        }
                    }
                },
                Some(Command::CaptureStopped) => {
                    speech.capture_ready = false;
                    invalidate_capture(processor, capture_epoch, speech); let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                    while pcm.try_recv().is_ok() {}
                    snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
                },
                Some(Command::SetCaptureReady { ready }) => {
                    speech.capture_ready = ready;
                    if !ready {
                        invalidate_capture(processor, capture_epoch, speech);
                        let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                        snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
                    }
                },
                Some(Command::SetSpeechMode { mode }) => {
                    if receive_prefs.set_speech_mode(mode, out) && speech.mode != mode {
                        speech.set_mode(mode);
                        flush_capture(processor, capture_epoch);
                        let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                        snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
                        receive_prefs.flush(storage.as_deref(), persist_tx, out, true);
                    }
                },
                Some(Command::PushToTalk { pressed, token }) => {
                    if speech.press(pressed, &token) {
                        flush_capture(processor, capture_epoch);
                        let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                        snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
                    }
                },
                Some(Command::SetReceiveAgc { enabled }) => { receive_prefs.set_agc(enabled, out); },
                Some(Command::SetUserVolume { server, client, uid, token, percent }) => {
                    if !subscribed { report_code(out, "connection_recovering", None); continue; }
                    if let Err(code) = receive.set_volume(&server, client, uid.as_deref(), &token, percent, receive_prefs) { report_code(out, code, None); }
                    for (id, queue) in audio.get_mut_queues() { queue.volume = receive.fields(*id).0 as f32 / 100.; }
                    snapshot(&con, out, &audio, voice.speaking(), storage.as_deref(), &receive);
                },
                Some(Command::SetNoiseSuppression { mode, request_id }) => {
                    if speech.mode == speech::Mode::PushToTalk {
                        invalidate_capture(processor, capture_epoch, speech);
                        let _ = voice.interrupt(|codec, data| send_voice(&mut con, codec, data));
                        snapshot(&con, out, &audio, false, storage.as_deref(), &receive);
                    }
                    processing.request_id=request_id; processing.requested.noise=mode; processing.request(processor,out);
                },
                Some(Command::SendChannelMessage { request_id, channel, message }) => {
                    let target = MessageTarget::Channel;
                    match outgoing_message(&con, request_id.clone(), target, Some(channel), None, message.clone()) {
                        Ok(chat) => {
                            let id = chat.id.clone();
                            chats.add(vec![chat]);
                            let send = con.get_state()?.send_message(target, &message).send_with_result(&mut con);
                            match send {
                                Ok(handle) => { operations.insert(handle, PendingOperation::Chat(id)); },
                                Err(_) => chats.finish(&id, ChatStatus::Failed, Some("message_send_failed".into())),
                            }

                        }
                        Err(error) => {
                            chats.add(vec![rejected_message(
                                &con,
                                request_id,
                                target,
                                Some(channel),
                                None,
                                message,
                                error.to_owned(),
                            )]);

                            report_code(out, error, None);
                        }
                    }
                },
                Some(Command::SendPrivateMessage { request_id, client, uid, message }) => {
                    let target = MessageTarget::Client(tsclientlib::ClientId(client));
                    match outgoing_message(&con, request_id.clone(), target, None, Some(&uid), message.clone()) {
                        Ok(chat) => {
                            let id = chat.id.clone();
                            chats.add(vec![chat]);
                            let send = con.get_state()?.send_message(target, &message).send_with_result(&mut con);
                            match send {
                                Ok(handle) => { operations.insert(handle, PendingOperation::Chat(id)); },
                                Err(_) => chats.finish(&id, ChatStatus::Failed, Some("message_send_failed".into())),
                            }

                        }
                        Err(error) => {
                            chats.add(vec![rejected_message(
                                &con,
                                request_id,
                                target,
                                None,
                                Some(&uid),
                                message,
                                error.to_owned(),
                            )]);

                            report_code(out, error, None);
                        }
                    }
                },
            },
            Some((epoch, samples)) = pcm.recv() => {
                if epoch != capture_epoch.load(Ordering::Acquire) { continue; }
                if speech.sending_allowed() && audio_send_allowed(input_muted, output_muted, subscribed, con.can_send_audio(),
                    is_joining(&operations)) {
                    if speech.mode == speech::Mode::PushToTalk {
                        processor.capture_pressed(&samples);
                    } else {
                        processor.capture(&samples);
                    }
                }
            },
            Some(event) = processor.events.recv() => {
                match event {
                    audio_processing::Event::Audio { generation, epoch, frame } => {
                        if epoch!=processor.epoch() || generation!=processing.active_generation || !speech.sending_allowed() || !audio_send_allowed(input_muted, output_muted, subscribed, con.can_send_audio(),
                            is_joining(&operations)) {continue}
                        // Resolve current channel/codec/permissions at consumption; no connection state leaves this loop.
                        let state=con.get_state()?;
                        let codec=state.clients.get(&state.own_client).and_then(|me|state.channels.get(&me.channel)).map(|c|c.codec);
                        let codec=match codec {
                            Some(tsclientlib::Codec::OpusVoice)=>CodecType::OpusVoice,
                            Some(tsclientlib::Codec::OpusMusic)=>CodecType::OpusMusic,
                            _=>{
                                invalidate_capture(processor, capture_epoch, speech); let _=voice.interrupt(|codec,data|send_voice(&mut con,codec,data));
                                input_muted=true;snapshot(&con,out,&audio,false,storage.as_deref(), &receive);report_audio_muted(out,"legacy_codec",None);continue
                            }
                        };
                        let speaking=voice.speaking();
                        if speech.mode == speech::Mode::PushToTalk {
                            voice.process_pressed(frame,codec,|codec,data|send_voice(&mut con,codec,data))?;
                        } else {
                            voice.process(frame,codec,Instant::now(),|codec,data|send_voice(&mut con,codec,data))?;
                        }
                        if speaking!=voice.speaking(){snapshot(&con,out,&audio,voice.speaking(),storage.as_deref(), &receive);}
                    },
                    audio_processing::Event::Gap{epoch}=>{
                        if epoch!=processor.epoch(){continue}
                        invalidate_capture(processor, capture_epoch, speech);
                        let _=voice.interrupt(|codec,data|send_voice(&mut con,codec,data));
                        snapshot(&con,out,&audio,false,storage.as_deref(), &receive);
                    },
                    event=>{if matches!(event,audio_processing::Event::Applied{..}){voice.switched();}processing.completed(event,out);},
                }
            },
            _ = clock.tick() => {
                let (expired, changed) = downloads.expire(Instant::now());
                for handle in expired { con.cancel_filetransfer(handle); }
                if changed || Instant::now() >= media_retry {
                    media_retry = Instant::now() + Duration::from_secs(1);
                    schedule_media(&mut con, storage.as_deref(), downloads);
                    schedule_badges(&con, storage.as_deref(), downloads, &media_tx);
                }
                receive_prefs.flush(storage.as_deref(), persist_tx, out, false);
                files.tick(&mut con, &mut operations, out);
                if let Some(quality) = network.expire(Instant::now()) {
                    out.lock().unwrap().set_network_quality(quality);
                }
                if playback_log_at.elapsed() >= Duration::from_secs(5) {
                    let counts = playback.diagnostics();
                    let delta: [u64; 5] = std::array::from_fn(|i| counts[i].wrapping_sub(playback_counts[i]));
                    let receive = audio.take_diagnostics();
                    if delta[0] > 0 || received_packets > 0 {
                        diagnostic!("Playback health elapsed_ms={} renders={} underruns={} missing_frames={} growth_silence_frames={} resets={} max_callback_frames={} queued_frames={} max_refill_gap_ms={} max_refill_work_us={} packets={} rejected={} end_batches={} receive_queues={} plc_frames={} fec_frames={} catchup_frames={} truncated_packets={} buffering_frames={} decode_errors={} last_packet_error={:?}",
                            playback_log_at.elapsed().as_millis(), delta[0], delta[1], delta[2], delta[3], delta[4],
                            playback.max_callback(), playback.available(), max_refill_gap_ms, max_refill_work_us,
                            received_packets, rejected_packets, ended_queues, audio.get_queues().len(),
                            receive.plc_frames, receive.fec_frames, receive.catchup_frames, receive.truncated_packets,
                            receive.buffering_frames, receive.decode_errors, last_packet_error);
                    }
                    playback_counts = counts;
                    playback_log_at = Instant::now();
                    max_refill_gap_ms = 0; max_refill_work_us = 0;
                    received_packets = 0; rejected_packets = 0; ended_queues = 0; last_packet_error.clear();
                }
            }
        }
    }
}

impl Bridge {
    pub fn new() -> Self {
        let (tx, mut rx) = mpsc::channel(32);
        let (pcm, mut pcm_rx) = mpsc::channel(5);
        let output = Arc::new(Mutex::new(Output::default()));
        output.lock().unwrap().status("disconnected");
        output
            .lock()
            .unwrap()
            .set_unread(UnreadState::default().snapshot());
        let avatar_store = Arc::new(avatar::Store::default());
        let worker_avatar_store = avatar_store.clone();
        let worker_output = output.clone();
        let (playback, refill) = playback::Playback::new().expect("playback wake socket");
        let playback = Arc::new(playback);
        let worker_playback = playback.clone();
        let capture_epoch = Arc::new(AtomicU64::new(0));
        let worker_capture_epoch = capture_epoch.clone();
        let persistence_output = output.clone();
        let (persist_tx, persist_rx) = std_mpsc::channel::<PersistJob>();
        let persistence_thread = std::thread::spawn(move || {
            while let Ok(first) = persist_rx.recv() {
                let mut latest = HashMap::from([(first.path, (first.data, first.error_code))]);
                while let Ok(job) = persist_rx.try_recv() {
                    latest.insert(job.path, (job.data, job.error_code));
                }
                for (path, (data, error_code)) in latest {
                    if let Err(error) = atomic_write(&path, &data) {
                        report_code(&persistence_output, error_code, Some(error.to_string()));
                    }
                }
            }
        });
        let chat_worker = chat::Worker::new(output.clone());
        let worker = std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();
            runtime.block_on(async {
                let refill = tokio::net::UnixDatagram::from_std(refill).unwrap();
                let mut storage: Option<PathBuf> = None;
                let mut app_active = false;
                let mut receive_prefs = receive_audio::Preferences::default();
                let mut speech = speech::State::default();
                speech.publish(false, &worker_output);
                let mut profile = profile::Profile::default();
                profile.publish(&worker_output);
                receive_prefs.publish(&worker_output);
                let mut save_clock = tokio::time::interval(Duration::from_millis(100));
                let mut processing = AudioProcessingState::default();
                let mut processor = audio_processing::Processor::new();
                let mut files = channel_files::Files::default();
                let mut downloads = media::Downloads::default();
                let (files_tx, mut files_rx) = mpsc::unbounded_channel();
                files.publish(&worker_output);
                loop {
                    let request = tokio::select! {
                        _=save_clock.tick()=>{receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, false);continue},
                        Some(update)=files_rx.recv()=>{files.update(update,&worker_output);continue},
                        event=processor.events.recv()=>{if let Some(event)=event{processing.completed(event,&worker_output);}continue},
                        request=rx.recv()=>match request{Some(r)=>r,None=>break},
                    };
                    match request {
                        Command::Shutdown => { receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, true); break; },
                        Command::SetAppActive { active } => { app_active = active; if !active { receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, true); } },
                        Command::SetCaptureReady { ready } => { speech.capture_ready = ready; },
                        Command::SetSpeechMode { mode } => {
                            if receive_prefs.set_speech_mode(mode, &worker_output) {
                                speech.set_mode(mode); speech.publish(false, &worker_output);
                                receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, true);
                            }
                        },
                        Command::PushToTalk { .. } | Command::CaptureStopped => {
                            speech.capture_ready = false; speech.invalidate(); speech.publish(false, &worker_output);
                        },
                        Command::SetReceiveAgc { enabled } => receive_prefs.set_agc(enabled, &worker_output),
                        Command::SetNickname { nickname, request_id } => { profile.save(&nickname, request_id, &worker_output); },
                        Command::SetUserVolume { .. } => report_code(&worker_output, "receive_audio_context_changed", None),
                        Command::SetNoiseSuppression { mode, request_id } => {processing.request_id=request_id;processing.requested.noise=mode;processing.request(&processor,&worker_output);},
                        Command::Configure { storage: value } => {
                            profile.load(Path::new(&value), &worker_output);
                            receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, true);
                            receive_prefs.load(Path::new(&value), &worker_output);
                            speech.set_mode(receive_prefs.speech_mode);
                            speech.publish(false, &worker_output);
                            processor.configure(PathBuf::from(&value));
                            audio_models::remove_retired_model_cache(Path::new(&value));
                            storage = Some(PathBuf::from(value));
                            avatar::offline(&worker_avatar_store, &worker_output).await;
                        }
                        request @ Command::Connect { .. } => {
                            // A protocol panic ends this connection, not the command receiver.
                            match std::panic::AssertUnwindSafe(session(
                                request,
                                &worker_playback,
                                &refill,
                                &mut rx,
                                &mut pcm_rx,
                                &worker_capture_epoch,
                                &worker_output,
                                storage.clone(),
                                &persist_tx,
                                &chat_worker,
                                &mut downloads,
                                &mut app_active,
                                &mut processing,
                                &mut processor,
                                &mut receive_prefs,
                                &mut speech,
                                &mut profile,
                                &worker_avatar_store,
                                &mut files,
                                &files_tx,
                                &mut files_rx,
                            ))
                            .catch_unwind()
                            .await
                            {
                                Ok(Ok(true)) => { receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, true); break; },
                                Ok(Err(error)) => report(&worker_output, error),
                                Err(panic) => {
                                    let detail = panic.downcast_ref::<String>().map(String::as_str)
                                        .or_else(|| panic.downcast_ref::<&str>().copied())
                                        .unwrap_or("unknown panic");
                                    report(&worker_output, format!("Connection task panicked: {detail}"));
                                }
                                _ => {}
                            }
                            receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, true);
                            downloads.cancel();
                            files.disconnected(&worker_output);
                            worker_playback.clear();
                            invalidate_capture(&mut processor, &worker_capture_epoch, &mut speech);
                            speech.capture_ready = false;
                            speech.available(false);
                            speech.publish(false, &worker_output);
                            worker_output.lock().unwrap().status("disconnected");
                            avatar::offline(&worker_avatar_store, &worker_output).await;
                            chat_worker.clear();
                            worker_output
                                .lock()
                                .unwrap()
                                .set_unread(UnreadState::default().snapshot());
                        }
                        Command::AvatarChanged => {
                            avatar::offline(&worker_avatar_store, &worker_output).await
                        }
                        Command::FileCacheInspect { request_id } => files.cache_command(request_id, false, storage.as_deref(), &files_tx, &worker_output),
                        Command::FileCacheClear { request_id } => files.cache_command(request_id, true, storage.as_deref(), &files_tx, &worker_output),
                        command @ (Command::FilesOpen { .. } | Command::FilesBack { .. } | Command::FilesList { .. } | Command::FilesSort { .. } | Command::FilesDownload { .. } | Command::FilesUpload { .. } | Command::FilesRetry { .. }) => {
                            files.offline(&command, storage.as_deref(), &worker_output);
                        }
                        _ => {}
                    }
                }
                receive_prefs.flush(storage.as_deref(), &persist_tx, &worker_output, true);
            });
            drop(chat_worker);
            drop(persist_tx);
            let _ = persistence_thread.join();
        });
        Self {
            worker: Some(worker),
            tx,
            pcm,
            capture_epoch,
            output,
            avatar_store,
            playback,
        }
    }
    pub fn send(&self, json: &str) -> Result<(), String> {
        let request: Command = serde_json::from_str(json).map_err(|error| error.to_string())?;
        request.validate().map_err(str::to_owned)?;
        match request {
            Command::AvatarSelect { selection } => {
                self.avatar_store
                    .selection
                    .fetch_max(selection, Ordering::AcqRel);
                Ok(())
            }
            Command::AvatarSet { selection, image } => {
                let permit = self.tx.try_reserve().map_err(|error| error.to_string())?;
                avatar::persist(
                    &self.avatar_store,
                    selection,
                    image.as_deref(),
                    &self.output,
                )?;
                permit.send(Command::AvatarChanged);
                Ok(())
            }
            Command::Configure { ref storage } => {
                let root = PathBuf::from(storage);
                let permit = self.tx.try_reserve().map_err(|error| error.to_string())?;
                let mut root_slot = self.avatar_store.root.lock().unwrap();
                if root_slot.as_ref().is_some_and(|existing| existing != &root) {
                    return Err("Avatar storage cannot change during this core lifetime".into());
                }
                *root_slot = Some(root);
                permit.send(request);
                Ok(())
            }
            _ => self.tx.try_send(request).map_err(|error| error.to_string()),
        }
    }
    pub fn poll(&self) -> Value { poll_output(&self.output) }

}
#[cfg(test)]
static POLL_LOCK_NANOS: AtomicU64 = AtomicU64::new(0);
fn poll_output(output: &Arc<Mutex<Output>>) -> Value {
        let (mut envelope, chats) = {
            let mut out = output.lock().unwrap();
            #[cfg(test)] let started = Instant::now();
            let events: Vec<_> = out.events.drain(..).collect();
            let chats = out.chat_snapshot.take().unwrap_or(Value::Null);
            let envelope = json!({"snapshot":out.snapshot,"events":events,"unread":out.unread,"channelFiles":out.channel_files,"fileCache":out.file_cache,"receiveAudio":out.receive_audio,"speech":out.speech,"profile":out.profile});
            #[cfg(test)] POLL_LOCK_NANOS.fetch_max(started.elapsed().as_nanos() as u64, Ordering::Relaxed);
            (envelope, chats)
        };
        // Move the history into the envelope. JSON macro serialization would deep-copy it.
        envelope.as_object_mut().unwrap().insert("chats".into(), chats);
        envelope
    }

impl Default for Bridge {
    fn default() -> Self {
        Self::new()
    }
}
impl Drop for Bridge {
    fn drop(&mut self) {
        self.output.lock().unwrap().notifier = None;
        let _ = futures::executor::block_on(self.tx.send(Command::Shutdown));
        if let Some(worker) = self.worker.take() { let _ = worker.join(); }
    }
}

// C ABI pointers are owned by the platform wrapper. Never call after ts_destroy.
#[no_mangle]
pub extern "C" fn ts_create() -> *mut Bridge {
    Box::into_raw(Box::new(Bridge::new()))
}
// Callback only schedules work: it must never reenter the bridge under this lock.
#[no_mangle]
pub unsafe extern "C" fn ts_set_notifier(
    handle: *mut Bridge,
    callback: Option<extern "C" fn(usize)>,
    context: usize,
) {
    if !handle.is_null() {
        (*handle).output.lock().unwrap().notifier = callback.map(|callback| (callback, context));
    }
}
#[no_mangle]
pub unsafe extern "C" fn ts_destroy(handle: *mut Bridge) {
    if !handle.is_null() {
        drop(Box::from_raw(handle));
    }
}
#[no_mangle]
pub unsafe extern "C" fn ts_command(handle: *mut Bridge, text: *const c_char) -> i32 {
    if handle.is_null() || text.is_null() {
        return -1;
    }
    let result = CStr::from_ptr(text)
        .to_str()
        .map_err(|error| error.to_string())
        .and_then(|value| (*handle).send(value));
    match result {
        Ok(()) => 0,
        Err(error) => {
            report(&(*handle).output, error);
            -1
        }
    }
}
#[no_mangle]
pub unsafe extern "C" fn ts_poll(handle: *mut Bridge) -> *mut c_char {
    if handle.is_null() {
        return std::ptr::null_mut();
    }
    CString::new((*handle).poll().to_string())
        .unwrap()
        .into_raw()
}
#[no_mangle]
pub unsafe extern "C" fn ts_free(text: *mut c_char) {
    if !text.is_null() {
        drop(CString::from_raw(text));
    }
}
#[no_mangle]
pub unsafe extern "C" fn ts_capture(handle: *mut Bridge, samples: *const i16, len: usize) -> i32 {
    if handle.is_null() || samples.is_null() || len != 960 {
        return -1;
    }
    let epoch = (*handle).capture_epoch.load(Ordering::Acquire);
    match (*handle)
        .pcm
        .try_send((epoch, std::slice::from_raw_parts(samples, len).to_vec()))
    {
        Ok(_) => 0,
        Err(_) => 1,
    }
}
#[no_mangle]
pub unsafe extern "C" fn ts_playback(
    handle: *mut Bridge,
    samples: *mut f32,
    capacity: usize,
) -> usize {
    if handle.is_null() || samples.is_null() || capacity < 2 {
        return 0;
    }
    (*handle)
        .playback
        .render(samples, samples.add(1), capacity / 2, 2)
        * 2
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn speech_mode_is_global_persistent_and_legacy_configs_default_to_automatic() {
        let root = std::env::temp_dir().join(format!("speech-mode-{}", received_id()));
        fs::create_dir_all(&root).unwrap();
        fs::write(root.join("receive-audio.json"), br#"{"agc":false,"users":{}}"#).unwrap();
        let bridge = Bridge::new();
        bridge.send(&json!({"type":"configure","storage":root}).to_string()).unwrap();
        wait_until(|| bridge.poll()["receiveAudio"]["agc"] == false);
        assert_eq!(bridge.poll()["speech"]["mode"], "automatic");
        for command in [r#"{"type":"set_speech_mode","mode":"vad"}"#, r#"{"type":"push_to_talk","pressed":true,"token":""}"#, r#"{"type":"set_capture_ready","ready":1}"#] {
            assert!(bridge.send(command).is_err());
        }
        bridge.send(r#"{"type":"set_speech_mode","mode":"push_to_talk"}"#).unwrap();
        wait_until(|| bridge.poll()["speech"]["mode"] == "push_to_talk");
        assert_eq!(bridge.poll()["speech"]["available"], false);
        drop(bridge);
        let restored = Bridge::new();
        restored.send(&json!({"type":"configure","storage":root}).to_string()).unwrap();
        wait_until(|| restored.poll()["speech"]["mode"] == "push_to_talk");
        let saved: Value = serde_json::from_slice(&fs::read(root.join("receive-audio.json")).unwrap()).unwrap();
        assert_eq!(saved["speechMode"], "push_to_talk");
        assert_eq!(saved["agc"], false);
        drop(restored);
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn user_status_preserves_input_and_groups_both_output_flags_for_display() {
        for input in [false, true] {
            for output in [false, true] {
                for output_only in [false, true] {
                    for away in [None, Some(""), Some("Back later")] {
                        assert_eq!(
                            UserStatus::new(input, output, output_only, away),
                            UserStatus {
                                muted: input,
                                deafened: output || output_only,
                                away: away.is_some(),
                            }
                        );
                    }
                }
            }
        }
    }
    #[test]
    fn receive_preferences_bridge_defaults_validation_and_restart() {
        let root = std::env::temp_dir().join(format!("receive-bridge-{}", received_id()));
        let bridge = Bridge::new();
        bridge
            .send(&json!({"type":"configure","storage":root}).to_string())
            .unwrap();
        wait_until(|| bridge.poll()["receiveAudio"]["agc"] == true);
        for command in [
            json!({"type":"set_receive_agc","enabled":1}),
            json!({"type":"set_user_volume","server":"s","client":1,"uid":"u","token":"t","percent":301}),
            json!({"type":"set_user_volume","server":"s","client":1,"uid":"u","token":"t","percent":-1}),
            json!({"type":"set_user_volume","server":"s","client":1,"uid":"u","token":"t","percent":1.5}),
            json!({"type":"set_user_volume","server":"s","client":1,"uid":"","token":"t","percent":100}),
            json!({"type":"set_user_volume","server":"s","client":0,"uid":null,"token":"t","percent":100}),
        ] {
            assert!(bridge.send(&command.to_string()).is_err());
        }
        for percent in [0, 100, 200, 201, 250, 300] {
            bridge.send(&json!({"type":"set_user_volume","server":"s","client":1,"uid":"u","token":"t","percent":percent}).to_string()).unwrap();
        }
        bridge
            .send(r#"{"type":"set_receive_agc","enabled":false}"#)
            .unwrap();
        wait_until(|| bridge.poll()["receiveAudio"]["agc"] == false);
        drop(bridge);
        wait_until(|| {
            std::fs::read(root.join("receive-audio.json"))
                .ok()
                .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
                .is_some_and(|v| v["agc"] == false)
        });
        let restored = Bridge::new();
        restored
            .send(&json!({"type":"configure","storage":root}).to_string())
            .unwrap();
        wait_until(|| restored.poll()["receiveAudio"]["agc"] == false);
        restored.send(r#"{"type":"set_user_volume","server":"s","client":1,"uid":null,"token":"old","percent":140}"#).unwrap();
        wait_until(|| {
            restored.poll()["events"]
                .as_array()
                .unwrap()
                .iter()
                .any(|e| e["code"] == "receive_audio_context_changed")
        });
        drop(restored);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    #[ignore = "requires an explicitly authorized read-only TeamSpeak server"]
    fn read_only_receive_members_and_reconnect() {
        let address = std::env::var("MOBILESPEAK_READONLY_SERVER")
            .expect("explicit read-only server authorization");
        let root = std::env::temp_dir().join(format!("receive-readonly-{}", received_id()));
        std::fs::create_dir_all(&root).unwrap();
        let bridge = Bridge::new();
        bridge
            .send(&json!({"type":"configure","storage":root}).to_string())
            .unwrap();
        let identity = Identity::create();
        bridge.send(&json!({"type":"set_nickname","nickname":"MobileSpeak read-only check","request_id":1}).to_string()).unwrap();
        let connect = json!({"type":"connect","address":address,"identity":identity}).to_string();
        let mut previous_server = None;
        for _ in 0..2 {
            bridge.send(&connect).unwrap();
            let deadline = Instant::now() + Duration::from_secs(35);
            let snapshot = loop {
                let poll = bridge.poll();
                let errors: Vec<_> = poll["events"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .filter(|e| e["type"] == "error")
                    .collect();
                assert!(errors.is_empty(), "{errors:?}");
                if poll["snapshot"]["status"] == "connected"
                    && poll["snapshot"]["clients"]
                        .as_array()
                        .is_some_and(|c| !c.is_empty())
                {
                    std::thread::sleep(Duration::from_secs(1));
                    let settled = bridge.poll();
                    let errors: Vec<_> = settled["events"]
                        .as_array()
                        .unwrap()
                        .iter()
                        .filter(|e| e["type"] == "error")
                        .collect();
                    assert!(errors.is_empty(), "{errors:?}");
                    break settled["snapshot"].clone();
                }
                assert!(Instant::now() < deadline, "connection timeout");
                std::thread::sleep(Duration::from_millis(50));
            };
            if let Some(server) = &previous_server {
                assert_eq!(server, &snapshot["serverId"]);
            }
            previous_server = Some(snapshot["serverId"].clone());
            let others: Vec<_> = snapshot["clients"]
                .as_array()
                .unwrap()
                .iter()
                .filter(|u| u["id"] != snapshot["ownClient"])
                .collect();
            assert!(others
                .iter()
                .all(|u| u["volumePercent"] == 100 && u["volumeToken"].is_string()));
            println!("Read-only subscription: {} channels, {} other online members, {} distinct member channels", snapshot["channels"].as_array().unwrap().len(), others.len(), others.iter().map(|u| u["channel"].as_u64().unwrap()).collect::<HashSet<_>>().len());
            // Never activate playback/capture or send a captured sample in this test.
            bridge.send(r#"{"type":"disconnect"}"#).unwrap();
            wait_until(|| bridge.poll()["snapshot"]["status"] == "disconnected");
        }
        drop(bridge);
        std::fs::remove_dir_all(root).unwrap();
    }
    fn wait_until(mut ready: impl FnMut() -> bool) {
        let deadline = Instant::now() + Duration::from_secs(5);
        while !ready() {
            assert!(Instant::now() < deadline, "worker did not recover");
            std::thread::sleep(Duration::from_millis(10));
        }
    }
    #[test]
    fn file_cache_commands_work_offline_and_preserve_other_app_data() {
        let root = std::env::temp_dir().join(format!("cache-bridge-{}", received_id()));
        fs::create_dir_all(root.join("channel-files/server/1")).unwrap();
        fs::create_dir_all(root.join("file-uploads")).unwrap();
        fs::write(root.join("channel-files/server/1/中文.txt"), b"download").unwrap();
        fs::write(root.join("file-uploads/source"), b"stage").unwrap();
        fs::write(root.join("original-export.txt"), b"keep").unwrap();
        let bridge = Bridge::new();
        bridge
            .send(&json!({"type":"configure","storage":root}).to_string())
            .unwrap();
        bridge
            .send(r#"{"type":"file_cache_inspect","request_id":1}"#)
            .unwrap();
        wait_until(|| bridge.output.lock().unwrap().file_cache["status"] == "ready");
        assert_eq!(bridge.poll()["fileCache"]["bytes"], 13);
        bridge
            .send(r#"{"type":"file_cache_clear","request_id":2}"#)
            .unwrap();
        wait_until(|| {
            bridge
                .output
                .lock()
                .unwrap()
                .events
                .iter()
                .any(|event| event["type"] == "file_cache_result" && event["requestId"] == 2)
        });
        let result = bridge.poll();
        assert_eq!(result["snapshot"]["status"], "disconnected");
        assert_eq!(result["fileCache"]["status"], "ready");
        assert_eq!(result["fileCache"]["bytes"], 0);
        assert_eq!(result["fileCache"]["items"], 0);
        let event = result["events"]
            .as_array()
            .unwrap()
            .iter()
            .find(|event| event["requestId"] == 2)
            .unwrap();
        assert!(event["error"].is_null());
        assert_eq!(event["removedBytes"], 13);
        assert_eq!(fs::read(root.join("original-export.txt")).unwrap(), b"keep");
        bridge.send(r#"{"type":"shutdown"}"#).unwrap();
        wait_until(|| bridge.tx.is_closed());
        fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn broken_stderr_does_not_end_sessions() {
        use std::io::Write;
        use std::os::fd::FromRawFd;
        if std::env::var_os("MOBILESPEAK_TEST_BROKEN_STDERR").is_some() {
            // An orphaned terminal returns the exact EIO (os error 5) from the
            // phone's report. Run in a child so no other test's stderr changes.
            let error = std::io::stderr().write(b"stderr probe\n").unwrap_err();
            assert_eq!(error.raw_os_error(), Some(5));
            let server = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
            let connect = json!({"type":"connect","address":server.local_addr().unwrap().to_string(),"name":"test"}).to_string();
            let bridge = Bridge::new();
            for _ in 0..2 {
                bridge.send(&connect).unwrap();
                wait_until(|| bridge.output.lock().unwrap().snapshot["status"] == "connecting");
                bridge.avatar_store.revision.fetch_add(1, Ordering::Release);
                bridge.tx.blocking_send(Command::AvatarChanged).unwrap();
                wait_until(|| {
                    let out = bridge.output.lock().unwrap();
                    if let Some(error) = out.events.iter().find(|e| e["type"] == "error") {
                        println!("native worker error: {}", error["detail"]);
                        panic!("diagnostic I/O ended a connection");
                    }
                    out.events
                        .iter()
                        .any(|e| e["type"] == "avatar_sync" && e["status"] == "idle")
                });
                assert_eq!(bridge.poll()["snapshot"]["status"], "connecting");
                bridge.send(r#"{"type":"disconnect"}"#).unwrap();
                wait_until(|| bridge.output.lock().unwrap().snapshot["status"] == "disconnected");
                assert!(!bridge.poll()["events"]
                    .as_array()
                    .unwrap()
                    .iter()
                    .any(|e| e["type"] == "error"));
            }
            bridge.send(r#"{"type":"shutdown"}"#).unwrap();
            wait_until(|| bridge.tx.is_closed());
            return;
        }
        #[cfg_attr(target_os = "linux", link(name = "util"))]
        extern "C" {
            fn openpty(
                master: *mut i32,
                slave: *mut i32,
                name: *mut c_char,
                term: *const std::ffi::c_void,
                size: *const std::ffi::c_void,
            ) -> i32;
        }
        let (mut master, mut slave) = (-1, -1);
        // SAFETY: openpty initializes both descriptors; each is owned by one File.
        let (master, slave) = unsafe {
            assert_eq!(
                openpty(
                    &mut master,
                    &mut slave,
                    std::ptr::null_mut(),
                    std::ptr::null(),
                    std::ptr::null()
                ),
                0
            );
            (fs::File::from_raw_fd(master), fs::File::from_raw_fd(slave))
        };
        drop(master);
        let output = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "tests::broken_stderr_does_not_end_sessions",
                "--nocapture",
            ])
            .env("MOBILESPEAK_TEST_BROKEN_STDERR", "1")
            .stderr(slave)
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stdout)
        );
    }
    #[test]
    fn session_panic_disconnects_and_keeps_the_bridge_reusable() {
        // An unresponsive loopback socket keeps the handshake pending without a real server.
        let server = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let connect = json!({"type":"connect","address":server.local_addr().unwrap().to_string(),"name":"test"}).to_string();
        let bridge = Bridge::new();
        bridge.output.lock().unwrap().status("connected");
        bridge.send(&connect).unwrap();
        bridge.tx.blocking_send(Command::PanicSession).unwrap();
        wait_until(|| {
            let output = bridge.output.lock().unwrap();
            output.snapshot["status"] == "disconnected"
                && output.events.iter().any(|event| {
                    event["detail"] == "Connection task panicked: injected session failure"
                })
        });
        assert!(!bridge.tx.is_closed());
        assert_eq!(bridge.poll()["chats"], json!([]));
        bridge.send(&connect).unwrap();
        wait_until(|| bridge.poll()["snapshot"]["status"] == "connecting");
        bridge.send(r#"{"type":"disconnect"}"#).unwrap();
        wait_until(|| bridge.poll()["snapshot"]["status"] == "disconnected");
    }
    #[test]
    fn pending_session_interruptions_and_reconnect_reject_old_push_to_talk_tokens() {
        let server = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
        let bridge = Bridge::new();
        bridge.send(r#"{"type":"set_speech_mode","mode":"push_to_talk"}"#).unwrap();
        bridge.send(r#"{"type":"set_app_active","active":true}"#).unwrap();
        bridge.send(r#"{"type":"set_capture_ready","ready":true}"#).unwrap();
        let connect = json!({"type":"connect","address":server.local_addr().unwrap().to_string()}).to_string();
        let token = || bridge.output.lock().unwrap().speech["token"].as_str().unwrap().to_owned();
        wait_until(|| bridge.poll()["speech"]["mode"] == "push_to_talk");
        let before_connect = token();
        bridge.send(&connect).unwrap();
        wait_until(|| bridge.poll()["snapshot"]["status"] == "connecting" && token() != before_connect);
        for command in [
            json!({"type":"mute","input":true,"output":false}),
            json!({"type":"mute","input":false,"output":true}),
            json!({"type":"capture_stopped"}),
            json!({"type":"set_capture_ready","ready":false}),
            json!({"type":"set_app_active","active":false}),
            json!({"type":"set_noise_suppression","mode":"none","request_id":1}),
        ] {
            let old = token();
            bridge.send(&command.to_string()).unwrap();
            wait_until(|| token() != old);
            bridge.send(&json!({"type":"push_to_talk","pressed":true,"token":old}).to_string()).unwrap();
            bridge.send(r#"{"type":"set_capture_ready","ready":true}"#).unwrap();
            unsafe { assert!(matches!(ts_capture(&bridge as *const Bridge as *mut Bridge, [100i16; 960].as_ptr(), 960), 0 | 1)); }
            let poll = bridge.poll();
            assert_eq!(poll["speech"]["available"], false);
            assert_eq!(poll["speech"]["transmitting"], false);
        }
        let previous_session = token();
        bridge.send(r#"{"type":"disconnect"}"#).unwrap();
        wait_until(|| bridge.poll()["snapshot"]["status"] == "disconnected");
        bridge.send(&connect).unwrap();
        wait_until(|| bridge.poll()["snapshot"]["status"] == "connecting" && token() != previous_session);
        bridge.send(&json!({"type":"push_to_talk","pressed":true,"token":previous_session}).to_string()).unwrap();
        bridge.send(r#"{"type":"disconnect"}"#).unwrap();
        wait_until(|| bridge.poll()["snapshot"]["status"] == "disconnected");
        assert_eq!(bridge.poll()["speech"]["transmitting"], false);
    }
    #[test]
    fn final_audio_gate_blocks_mute_deafen_permissions_disconnect_and_join() {
        for bits in 0..32 {
            let flags: [bool; 5] = std::array::from_fn(|index| bits & (1 << index) != 0);
            assert_eq!(audio_send_allowed(flags[0], flags[1], flags[2], flags[3], flags[4]),
                !flags[0] && !flags[1] && flags[2] && flags[3] && !flags[4]);
        }
    }
    #[test]
    fn voice_encryption_follows_server_mode_and_known_channel_setting() {
        for is_unencrypted in [None, Some(false), Some(true)] {
            assert!(voice_encryption(
                CodecEncryptionMode::ForcedOn,
                is_unencrypted
            ));
            assert!(!voice_encryption(
                CodecEncryptionMode::ForcedOff,
                is_unencrypted
            ));
        }
        assert!(voice_encryption(CodecEncryptionMode::PerChannel, None));
        assert!(voice_encryption(
            CodecEncryptionMode::PerChannel,
            Some(false)
        ));
        assert!(!voice_encryption(
            CodecEncryptionMode::PerChannel,
            Some(true)
        ));
    }
    #[test]
    fn rnnoise_processes_each_twenty_ms_packet_as_two_ten_ms_frames() {
        let samples: [i16; 960] = std::array::from_fn(|index| {
            ((index as f32 * 440.0 * 2.0 * std::f32::consts::PI / 48_000.0).sin() * 12_000.0) as i16
        });
        let mut state = DenoiseState::new();
        assert!(denoise_packet(&samples[..959], &mut state).is_none());
        let actual = denoise_packet(&samples, &mut state).unwrap();
        let mut reference = DenoiseState::new();
        let mut expected = [0i16; 960];
        for (input, output) in samples
            .chunks_exact(480)
            .zip(expected.chunks_exact_mut(480))
        {
            let input: Vec<f32> = input.iter().map(|sample| f32::from(*sample)).collect();
            let mut frame = [0f32; 480];
            reference.process_frame(&mut frame, &input);
            for (value, sample) in frame.iter().zip(output) {
                *sample = value
                    .round()
                    .clamp(f32::from(i16::MIN), f32::from(i16::MAX))
                    as i16;
            }
        }
        assert_eq!(actual.0, expected);
        assert_ne!(actual.0, samples);
    }
    #[test]
    fn unread_counts_only_new_unseen_messages_in_the_current_server_and_channel() {
        fn message(kind: ChatKind, target: &str) -> ChatMessage {
            ChatMessage {
                id: "new".into(),
                conversation: format!(
                    "{}:{target}",
                    if matches!(kind, ChatKind::Channel) {
                        "channel"
                    } else {
                        "client"
                    }
                ),
                kind,
                target_id: target.into(),
                target_name: String::new(),
                sender_uid: "sender".into(),
                sender_name: String::new(),
                sender_avatar_hash: String::new(),
                own: false,
                text: "hello".into(),
                timestamp: 0,
                status: ChatStatus::Received,
                error: None,
            }
        }
        let mut unread = UnreadState::default();
        unread.sync(Some("server-a".into()), Some("channel:a".into()));
        unread.set_app_active(true);
        unread.record(&message(ChatKind::Channel, "a"));
        unread.record(&message(ChatKind::Channel, "other"));
        let mut own = message(ChatKind::Channel, "a");
        own.own = true;
        unread.record(&own);
        assert_eq!(unread.channel_count, 1);

        unread.record(&message(ChatKind::Private, "alice"));
        unread.record(&message(ChatKind::Private, "bob"));
        unread.set_visible("server-a", "client:alice", "first", true);
        assert_eq!(unread.private_counts.get("alice"), None);
        assert_eq!(unread.private_counts.get("bob"), Some(&1));
        unread.record(&message(ChatKind::Private, "alice"));
        assert_eq!(unread.private_counts.get("alice"), None);
        unread.set_app_active(false);
        unread.record(&message(ChatKind::Private, "alice"));
        assert_eq!(unread.private_counts.get("alice"), Some(&1));
        unread.set_app_active(true);
        assert_eq!(unread.private_counts.get("alice"), None);
        unread.set_visible("server-a", "client:alice", "stale", false);
        unread.set_visible("server-a", "channel:a", "current", true);
        assert_eq!(unread.channel_count, 0);
        unread.set_visible("server-a", "channel:a", "stale", false);
        unread.record(&message(ChatKind::Channel, "a"));
        assert_eq!(unread.channel_count, 0);

        unread.set_visible("server-a", "channel:a", "current", false);
        unread.record(&message(ChatKind::Channel, "a"));
        unread.sync(Some("server-a".into()), Some("channel:b".into()));
        assert_eq!(unread.channel_count, 0);
        assert_eq!(unread.private_counts.get("bob"), Some(&1));
        unread.sync(None, None);
        assert_eq!(unread.private_counts.get("bob"), Some(&1));
        unread.sync(Some("server-b".into()), Some("channel:a".into()));
        assert!(unread.private_counts.is_empty());
        unread.set_visible("server-a", "channel:a", "stale", true);
        assert!(unread.visible.is_none());
    }
    #[test]
    fn notifications_only_fire_for_changed_state_or_events() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        extern "C" fn notified(context: usize) {
            unsafe { &*(context as *const AtomicUsize) }.fetch_add(1, Ordering::SeqCst);
        }
        let count = AtomicUsize::new(0);
        let mut out = Output::default();
        out.notifier = Some((notified, &count as *const AtomicUsize as usize));
        out.status("disconnected");
        out.status("disconnected");
        assert_eq!(count.load(Ordering::SeqCst), 1);
        out.event(json!({"type":"error","code":"core_error","detail":"test"}));
        assert_eq!(count.load(Ordering::SeqCst), 2);
        let now = Instant::now();
        let mut history = network_quality::History::new(now);
        let mut stats = tsclientlib::ConnectionStats::default();
        stats.rtt = Duration::from_millis(42);
        stats.rtt_dev = Duration::from_millis(6);
        stats.rtt_measurements = 1;
        stats.rtt_measured_at = Some(now.into());
        let quality = history.update(Some(&stats), now);
        out.set_network_quality(quality); // Offline updates must not revive a session.
        assert_eq!(count.load(Ordering::SeqCst), 2);
        out.status("connected");
        out.set_network_quality(quality);
        out.set_network_quality(quality);
        assert_eq!(count.load(Ordering::SeqCst), 4);
        assert_eq!(out.snapshot["networkQuality"]["packetLossPercent"], 0.0);
        assert_eq!(
            out.snapshot["networkQuality"]["samples"][0]["packetLossPercent"],
            0.0
        );
        assert_eq!(
            out.snapshot["networkQuality"]["samples"][0]["packetLossGrade"],
            "good"
        );
        assert_eq!(
            out.snapshot["networkQuality"]["samples"]
                .as_array()
                .unwrap()
                .len(),
            1
        );
        out.set_snapshot(json!({"status":"connected","channels":[],"clients":[]}));
        assert_eq!(count.load(Ordering::SeqCst), 4); // Audio/bookkeeping snapshots retain network data.
        out.status("reconnecting");
        assert!(out.snapshot["networkQuality"].is_null());
        assert!(out.network_quality.is_none());
        assert_eq!(count.load(Ordering::SeqCst), 5);
    }
    #[test]
    fn retired_audio_settings_migrate_without_changing_other_config() {
        for (stored, expected) in [
            ("dpdfnet8", NoiseSuppressionMode::Rnnoise),
            ("dpdfnet2", NoiseSuppressionMode::Dpdfnet2),
            ("rnnoise", NoiseSuppressionMode::Rnnoise),
            ("none", NoiseSuppressionMode::None),
        ] {
            let value = json!({"noise": stored, "vad": "silero", "bookmark": "preserved"});
            let selection: audio_models::Selection = serde_json::from_value(value.clone()).unwrap();
            assert_eq!(selection.noise, expected);
            let command: Command = serde_json::from_value(json!({"type":"set_audio_processing", "noise":stored, "vad":"silero", "request_id":9})).unwrap();
            assert!(
                matches!(command, Command::SetNoiseSuppression { mode, request_id:9 } if mode == expected)
            );
            assert_eq!(
                serde_json::to_value(selection)
                    .unwrap()
                    .as_object()
                    .unwrap()
                    .len(),
                1
            );
        }
        assert!(
            serde_json::from_value::<Command>(json!({"type":"set_vad","mode":"silero"})).is_err()
        );
    }
    #[test]
    fn invalid_commands_are_rejected() {
        let bridge = Bridge::new();
        assert!(bridge
            .send(r#"{"type":"connect","address":"","name":"x"}"#)
            .is_err());
        assert!(bridge.send(r#"{"type":"join","channel":0}"#).is_err());
        assert!(bridge
            .send(r#"{"type":"send_channel_message","request_id":"1","channel":1,"message":" "}"#)
            .is_err());
        assert!(bridge
            .send(r#"{"type":"join","channel":1,"password":"x | y\\z"}"#)
            .is_ok());
        assert!(bridge.send("not json").is_err());
        assert!(bridge
            .send(r#"{"type":"set_noise_suppression","mode":"none"}"#)
            .is_ok());
        assert!(bridge
            .send(r#"{"type":"set_noise_suppression","mode":"rnnoise"}"#)
            .is_ok());
        assert!(bridge
            .send(r#"{"type":"set_noise_suppression","mode":"bad"}"#)
            .is_err());
        assert!(bridge
            .send(
                r#"{"type":"send_channel_message","request_id":"uuid","channel":123,"message":"你好"}"#
            )
            .is_ok());
        assert_eq!(bridge.poll()["snapshot"]["status"], "disconnected");
    }
    #[test]
    fn poll_keeps_state_and_consumes_events_and_chat_updates_once() {
        let bridge = Bridge::new();
        report_code(&bridge.output, "join_channel_failed", Some("test".into()));
        {
            let mut output = bridge.output.lock().unwrap();
            output.set_chats(json!([]));
        }
        let first = bridge.poll();
        assert_eq!(first["snapshot"]["status"], "disconnected");
        assert_eq!(first["events"].as_array().unwrap().len(), 1);
        assert_eq!(first["events"][0]["code"], "join_channel_failed");
        assert_eq!(first["events"][0]["detail"], "test");
        assert!(first["events"][0].get("message").is_none());
        assert_eq!(first["chats"], json!([]));
        assert!(first["unread"].is_object());

        let second = bridge.poll();
        assert_eq!(second["snapshot"], first["snapshot"]);
        assert_eq!(second["unread"], first["unread"]);
        assert_eq!(second["events"], json!([]));
        assert!(second["chats"].is_null());
    }
    #[test]
    fn cache_keys_isolate_server_resource_type_and_revision() {
        let root = Path::new("/cache");
        assert_ne!(
            avatar_path(root, "server-a", "uid", "hash"),
            avatar_path(root, "server-b", "uid", "hash")
        );
        assert_ne!(
            avatar_path(root, "server", "uid", "one"),
            avatar_path(root, "server", "uid", "two")
        );
        assert_ne!(
            avatar_path(root, "server", "uid", "1"),
            server_icon_path(root, "server", 1)
        );
        assert_ne!(
            server_icon_path(root, "server-a", 1),
            server_icon_path(root, "server-b", 1)
        );
        let badge = &known_badges()[0];
        assert_ne!(
            badge_icon_path(root, badge),
            server_icon_path(root, "server", 1)
        );
        assert!(builtin_group_icon(100));
        assert!(builtin_group_icon(300));
        assert!(!builtin_group_icon(166401896));
    }
    #[test]
    fn client_badges_use_known_metadata_and_preserve_server_order() {
        let parsed = client_badges(
            "Overwolf=0:badges=unknown,0005232e-538e-4cb2-93b6-d7d83e873829,0005232e-538e-4cb2-93b6-d7d83e873829,2bf80270-8efe-46dc-a472-3280a0479145",
        );
        assert_eq!(
            parsed
                .iter()
                .map(|badge| badge.name.as_str())
                .collect::<Vec<_>>(),
            ["PietSmiet", "Alpha Tester"]
        );
        assert!(client_badges("Overwolf=0").is_empty());
    }
    #[test]
    fn image_cache_accepts_supported_formats_only() {
        assert!(image_data_valid(b"\x89PNG\r\n\x1a\nrest"));
        assert!(image_data_valid(b"\xff\xd8\xffrest"));
        assert!(!image_data_valid(b"not an image"));
    }
}

// Retained separately from Bridge so platform stream teardown owns callback lifetime.
#[no_mangle]
pub unsafe extern "C" fn ts_playback_acquire(handle: *mut Bridge) -> *const playback::Playback {
    if handle.is_null() {
        return std::ptr::null();
    }
    Arc::into_raw((*handle).playback.clone())
}
#[no_mangle]
pub unsafe extern "C" fn ts_playback_release(handle: *const playback::Playback) {
    if !handle.is_null() {
        drop(Arc::from_raw(handle));
    }
}
#[no_mangle]
pub unsafe extern "C" fn ts_playback_active(handle: *const playback::Playback, active: bool) {
    if let Some(p) = handle.as_ref() {
        p.set_active(active);
    }
}
#[no_mangle]
pub unsafe extern "C" fn ts_render(
    handle: *const playback::Playback,
    left: *mut f32,
    right: *mut f32,
    frames: usize,
    stride: usize,
) -> usize {
    if handle.is_null() || left.is_null() || !(1..=2).contains(&stride) {
        return 0;
    }
    (*handle).render(left, right, frames, stride)
}
#[no_mangle]
pub unsafe extern "C" fn ts_render_count(handle: *const playback::Playback) -> u64 {
    handle
        .as_ref()
        .map_or(0, |p| p.renders.load(Ordering::Relaxed))
}

#[cfg(test)]
mod contract_regressions {
    use super::*;
    #[test]
    fn shared_bridge_samples_match_rust_output() {
        let mut samples = Vec::new();
        let output = Arc::new(Mutex::new(Output::default()));
        let mut legacy = Output::default(); legacy.status("disconnected");
        let mut missing = legacy.snapshot.clone(); missing.as_object_mut().unwrap().remove("connectionId");
        samples.push(json!({"snapshot":missing,"events":[],"chats":null}));
        for status in ["connecting", "connected", "reconnecting", "disconnected"] {
            let mut out = output.lock().unwrap();
            out.connection_id = Some("fixture-connection".into()); out.status(status);
            out.set_unread(UnreadState::default().snapshot());
            out.receive_audio = json!({"agc":true});
            out.speech = json!({"mode":"push_to_talk","token":"fixture-speech","available":status=="connected","transmitting":false});
            out.profile = profile::Profile::default().snapshot();
            out.set_chats(json!([]));
            if status == "connected" {
                let store = ChatStore { messages: vec![ChatMessage { id:"fixture-message".into(), conversation:"channel:id:1".into(), kind:ChatKind::Channel, target_id:"id:1".into(), target_name:"Lobby".into(), sender_uid:"uid".into(), sender_name:"Speaker".into(), sender_avatar_hash:String::new(), own:true, text:"contract".into(), timestamp:1, status:ChatStatus::Sent, error:None }] };
                out.set_chats(chat_snapshot(&store, None, None));
            }
            if status == "disconnected" { out.event(json!({"type":"error","code":"core_error","detail":"fixture failure"})); }
            drop(out);
            let mut processing = AudioProcessingState::default();
            processing.requested.noise = NoiseSuppressionMode::Dpdfnet2;
            processing.publish(&output, "switching", None);
            samples.push(poll_output(&output));
        }
        let data = serde_json::to_string_pretty(&samples).unwrap() + "\n";
        let path = Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/contracts/bridge.json");
        if std::env::var_os("UPDATE_BRIDGE_FIXTURES").is_some() { fs::write(&path, &data).unwrap(); }
        assert_eq!(fs::read_to_string(path).unwrap(), data);
    }
    #[test]
    fn connection_identity_survives_terminal_status_and_global_events_are_unscoped() {
        let mut out = Output::default(); out.connection_id = Some("new".into());
        for status in ["connecting", "connected", "reconnecting", "disconnected"] {
            out.status(status); assert_eq!(out.snapshot["connectionId"], "new");
        }
        out.event(json!({"type":"error","code":"core_error"}));
        out.event(json!({"type":"identity","value":{}}));
        out.event(json!({"type":"audio_processing"}));
        assert_eq!(out.events[0]["connectionId"], "new");
        assert!(out.events[1].get("connectionId").is_none());
        assert!(out.events[2].get("connectionId").is_none());
        let legacy: Command = serde_json::from_value(json!({"type":"connect","address":"localhost","name":"test"})).unwrap();
        assert!(matches!(legacy, Command::Connect { connection_id: None, .. }));
    }
    #[test]
    fn every_connect_uses_profile_instead_of_legacy_name_data() {
        for name in [json!("Old bookmark nickname"), json!(""), json!({"legacy":true})] {
            let command: Command = serde_json::from_value(json!({"type":"connect","address":"localhost:9987","name":name})).unwrap();
            assert!(command.validate().is_ok());
            assert!(matches!(command, Command::Connect { address, .. } if address == "localhost:9987"));
        }
        let command: Command = serde_json::from_value(json!({"type":"connect","address":"localhost:9987"})).unwrap();
        assert!(command.validate().is_ok());
    }
    #[test]
    fn join_gate_tracks_all_requests_and_cleanup() {
        let mut operations = HashMap::new();
        operations.insert(MessageHandle(1), PendingOperation::Join);
        operations.insert(MessageHandle(2), PendingOperation::Join);
        assert!(!audio_send_allowed(false, false, true, true, is_joining(&operations)));
        operations.remove(&MessageHandle(1)); // success or failure completes only this operation
        assert!(is_joining(&operations));
        operations.remove(&MessageHandle(2));
        assert!(audio_send_allowed(false, false, true, true, is_joining(&operations)));
        operations.insert(MessageHandle(3), PendingOperation::Join);
        operations.clear(); // disconnect/reconnect cancels every outstanding operation
        assert!(!is_joining(&operations));
    }
}
