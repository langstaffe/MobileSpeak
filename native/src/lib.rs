//! Mobile-only C ABI. TeamSpeak protocol state, chat and file transfers live on one Rust worker.
#[cfg(target_os = "android")]
mod android_jni;
mod badges;

use audiopus::{coder::Encoder, Application, Channels, SampleRate};
use badges::{known_badges, BadgeMetadata};
use futures::StreamExt;
use nnnoiseless::DenoiseState;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    ffi::{c_char, CStr, CString},
    fs,
    panic::{catch_unwind, AssertUnwindSafe},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    sync::{mpsc as std_mpsc, Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::{io::AsyncReadExt, sync::mpsc};
use tsclientlib::prelude::*;
use tsclientlib::{
    audio::AudioHandler, events::Event, ChannelId, ChannelType, Connection, DisconnectOptions,
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
        address: String,
        name: String,
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
    SetNoiseSuppression {
        mode: NoiseSuppressionMode,
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
    Disconnect,
    Shutdown,
}
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum NoiseSuppressionMode {
    #[default]
    Rnnoise,
    None,
}

fn new_denoiser(mode: NoiseSuppressionMode) -> Option<Box<DenoiseState<'static>>> {
    if mode == NoiseSuppressionMode::None {
        return None;
    }
    match catch_unwind(DenoiseState::new) {
        Ok(state) => Some(state),
        Err(_) => {
            eprintln!("RNNoise initialization failed; sending unprocessed microphone audio");
            None
        }
    }
}

// The capture bridge supplies one 20 ms, 48 kHz mono i16 packet. RNNoise consumes
// 10 ms float frames in the i16 amplitude range, so no extra buffering is needed.
fn denoise_packet(samples: &[i16], state: &mut DenoiseState<'_>) -> Option<[i16; 960]> {
    if samples.len() != 960 {
        return None;
    }
    let mut output = [0i16; 960];
    let mut input_frame = [0f32; DenoiseState::FRAME_SIZE];
    let mut output_frame = [0f32; DenoiseState::FRAME_SIZE];
    for (input, output) in samples
        .chunks_exact(DenoiseState::FRAME_SIZE)
        .zip(output.chunks_exact_mut(DenoiseState::FRAME_SIZE))
    {
        for (sample, value) in input.iter().zip(&mut input_frame) {
            *value = f32::from(*sample);
        }
        state.process_frame(&mut output_frame, &input_frame);
        for (value, sample) in output_frame.iter().zip(output) {
            if !value.is_finite() {
                return None;
            }
            *sample = value
                .round()
                .clamp(f32::from(i16::MIN), f32::from(i16::MAX)) as i16;
        }
    }
    Some(output)
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
            Self::Connect {
                address,
                name,
                password,
                ..
            } if address.trim().is_empty()
                || address.len() > 1024
                || name.trim().is_empty()
                || name.len() > 128
                || password.len() > 1024 =>
            {
                Err("Invalid server address, nickname or password")
            }
            Self::Join { channel, password } if *channel == 0 || password.len() > 1024 => {
                Err("Invalid channel")
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
    fn finish(&mut self, id: &str, status: ChatStatus, error: Option<String>) {
        if let Some(message) = self.messages.iter_mut().find(|message| message.id == id) {
            message.status = status;
            message.error = error;
        }
    }
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
    snapshot: Value,
    chat_snapshot: Option<Value>,
    unread: Value,
    events: VecDeque<Value>,
    audio: VecDeque<Vec<f32>>,
    notifier: Option<(extern "C" fn(usize), usize)>,
}
impl Output {
    fn notify(&self) {
        if let Some((callback, context)) = self.notifier {
            callback(context);
        }
    }
    fn set_snapshot(&mut self, value: Value) {
        if self.snapshot != value {
            self.snapshot = value;
            self.notify();
        }
    }
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
    fn event(&mut self, event: Value) {
        if self.events.len() >= 64 {
            self.events.pop_front();
        }
        self.events.push_back(event);
        self.notify();
    }
    fn status(&mut self, status: &str) {
        self.set_snapshot(json!({"status":status,"channels":[],"clients":[]}));
        self.audio.clear();
    }
}

pub struct Bridge {
    tx: mpsc::Sender<Command>,
    pcm: mpsc::Sender<Vec<i16>>,
    output: Arc<Mutex<Output>>,
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
fn snapshot(
    con: &Connection,
    out: &Arc<Mutex<Output>>,
    audio: &AudioHandler,
    storage: Option<&Path>,
) {
    let Ok(state) = con.get_state() else { return };
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
            json!({
                "id":client.id.0,"channel":client.channel.0,"uid":uid,"name":client.name,
                "avatarHash":client.avatar_hash,"avatarPath":avatar_path,
                "badges":badges,"serverGroupIcons":server_group_icons,"channelGroupIcon":channel_group_icon,
                "muted":client.input_muted,"deafened":client.output_muted,
                "speaking":audio.get_queues().contains_key(&client.id)
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
}
fn persist_chat(
    tx: &std_mpsc::Sender<PersistJob>,
    root: Option<&Path>,
    server: Option<&str>,
    store: &ChatStore,
) {
    let (Some(root), Some(server)) = (root, server) else {
        return;
    };
    if let Ok(data) = serde_json::to_vec(store) {
        let _ = tx.send(PersistJob {
            path: chat_path(root, server),
            data,
        });
    }
}
fn chat_snapshot(store: &ChatStore, storage: Option<&Path>, server: Option<&str>) -> Value {
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
                            path_if_cached(avatar_path(
                                root,
                                server,
                                &message.sender_uid,
                                &message.sender_avatar_hash,
                            ))
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
fn publish_chat(
    out: &Arc<Mutex<Output>>,
    store: &ChatStore,
    storage: Option<&Path>,
    server: Option<&str>,
) {
    out.lock()
        .unwrap()
        .set_chats(chat_snapshot(store, storage, server));
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
            remote: format!("/avatar_{}", uid.as_avatar()),
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
fn schedule_media(
    con: &mut Connection,
    storage: Option<&Path>,
    active: &mut HashSet<String>,
    failed: &mut HashSet<String>,
    transfers: &mut HashMap<FiletransferHandle, MediaRequest>,
) {
    let Some(storage) = storage else { return };
    for request in desired_media(con, storage) {
        if transfers.len() >= MAX_MEDIA_TRANSFERS {
            break;
        }
        if request.local.is_file() || active.contains(&request.key) || failed.contains(&request.key)
        {
            continue;
        }
        match con.download_file(ChannelId(0), &request.remote, None, None) {
            Ok(handle) => {
                active.insert(request.key.clone());
                transfers.insert(handle, request);
            }
            Err(_) => {
                failed.insert(request.key);
            }
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
fn schedule_badges(
    con: &Connection,
    storage: Option<&Path>,
    active: &mut HashSet<String>,
    failed: &HashSet<String>,
    tx: &mpsc::UnboundedSender<(BadgeRequest, Result<(), String>)>,
) {
    let Some(storage) = storage else { return };
    for request in desired_badges(con, storage) {
        if active.len() >= MAX_MEDIA_TRANSFERS {
            break;
        }
        if request.local.is_file() || active.contains(&request.key) || failed.contains(&request.key)
        {
            continue;
        }
        active.insert(request.key.clone());
        let tx = tx.clone();
        tokio::spawn(async move {
            let result = download_badge(&request).await;
            let _ = tx.send((request, result));
        });
    }
}
async fn download_badge(request: &BadgeRequest) -> Result<(), String> {
    let client = reqwest::Client::builder()
        .timeout(Duration::from_secs(15))
        .build()
        .map_err(|error| error.to_string())?;
    let mut response = client
        .get(&request.url)
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|error| error.to_string())?;
    if response
        .content_length()
        .is_some_and(|size| size == 0 || size > MAX_IMAGE_BYTES)
    {
        return Err("Invalid badge image size".into());
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|error| error.to_string())? {
        if bytes.len() + chunk.len() > MAX_IMAGE_BYTES as usize {
            return Err("Badge image is too large".into());
        }
        bytes.extend_from_slice(&chunk);
    }
    if !image_data_valid(&bytes) {
        return Err("Invalid badge image data".into());
    }
    let path = request.local.clone();
    tokio::task::spawn_blocking(move || atomic_write(&path, &bytes))
        .await
        .map_err(|error| error.to_string())?
        .map_err(|error| error.to_string())
}

enum PendingOperation {
    Chat(String),
    Other(&'static str),
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

async fn session(
    first: Command,
    rx: &mut mpsc::Receiver<Command>,
    pcm: &mut mpsc::Receiver<Vec<i16>>,
    out: &Arc<Mutex<Output>>,
    storage: Option<PathBuf>,
    persist_tx: &std_mpsc::Sender<PersistJob>,
    app_active: &mut bool,
    noise_suppression: &mut NoiseSuppressionMode,
) -> Result<bool, Box<dyn std::error::Error>> {
    let Command::Connect {
        address,
        name,
        password,
        identity,
    } = first
    else {
        return Ok(false);
    };
    out.lock().unwrap().status("connecting");
    out.lock().unwrap().set_chats(json!([]));
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
    out.lock()
        .unwrap()
        .event(json!({"type":"identity","value":identity}));
    let mut con = Connection::build(address)
        .name(name)
        .password(password)
        .identity(identity)
        .input_muted(true)
        .output_muted(false)
        .input_hardware_enabled(true)
        .output_hardware_enabled(true)
        .connect()?;
    let mut audio = AudioHandler::new();
    let encoder = Encoder::new(SampleRate::Hz48000, Channels::Mono, Application::Voip)?;
    let mut denoiser = new_denoiser(*noise_suppression);
    let mut input_muted = true;
    let mut output_muted = false;
    let mut subscribed = false;
    let mut first_connection = true;
    let mut ticks = 0;
    let mut current_server: Option<String> = None;
    let mut chats = ChatStore::default();
    let mut chat_writable = true;
    let mut operations = HashMap::<MessageHandle, PendingOperation>::new();
    let mut media_active = HashSet::<String>::new();
    let mut media_failed = HashSet::<String>::new();
    let mut transfers = HashMap::<FiletransferHandle, MediaRequest>::new();
    let (media_tx, mut media_rx) = mpsc::unbounded_channel::<(MediaRequest, Result<(), String>)>();
    let mut badge_active = HashSet::<String>::new();
    let mut badge_failed = HashSet::<String>::new();
    let (badge_tx, mut badge_rx) = mpsc::unbounded_channel::<(BadgeRequest, Result<(), String>)>();
    let mut clock = tokio::time::interval(Duration::from_millis(20));
    clock.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
    let deadline = tokio::time::sleep(Duration::from_secs(30));
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            _ = &mut deadline, if first_connection => return Err("Connection timed out after 30 seconds".into()),
            event = async { con.events().next().await } => match event {
                Some(Ok(StreamItem::BookEvents(events))) => {
                    if !subscribed && con.get_state().is_ok() {
                        let handle = con
                            .get_state()?
                            .server
                            .set_subscribed(true)
                            .send_with_result(&mut con)?;
                        operations.insert(handle, PendingOperation::Other("channel_subscribe_failed"));
                        subscribed = true;
                        first_connection = false;
                    }
                    let connected_server = server_id(&con);
                    if current_server != connected_server {
                        current_server = connected_server;
                        chats = ChatStore::default();
                        chat_writable = true;
                        if let (Some(root), Some(server)) = (storage.as_deref(), current_server.as_deref()) {
                            match load_chat(root, server) {
                                Ok(mut loaded) => {
                                    if loaded.fail_pending("message_unconfirmed_after_restart") {
                                        persist_chat(persist_tx, Some(root), Some(server), &loaded);
                                    }
                                    chats = loaded;
                                }
                                Err(error) => {
                                    chat_writable = false;
                                    report_code(out, "chat_history_read_failed", Some(error));
                                }
                            }
                        }
                        publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                    }
                    unread.sync(current_server.clone(), current_channel_conversation(&con));
                    let received = received_messages(events, &con);
                    if !received.is_empty() {
                        for message in &received { unread.record(message); }
                        chats.messages.extend(received);
                        if chat_writable { persist_chat(persist_tx, storage.as_deref(), current_server.as_deref(), &chats); }
                        publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                    }
                    out.lock().unwrap().set_unread(unread.snapshot());
                    snapshot(&con, out, &audio, storage.as_deref());
                    schedule_media(&mut con, storage.as_deref(), &mut media_active, &mut media_failed, &mut transfers);
                    schedule_badges(&con, storage.as_deref(), &mut badge_active, &badge_failed, &badge_tx);
                },
                Some(Ok(StreamItem::IdentityLevelIncreased)) => {
                    out.lock().unwrap().event(json!({"type":"identity","value":con.get_options().get_identity()}));
                },
                Some(Ok(StreamItem::IdentityLevelIncreasing(level))) => {
                    if level > 24 { con.cancel_identity_level_increase(); return Err("Server requires identity level above 24; import a higher-level identity".into()); }
                },
                Some(Ok(StreamItem::Audio(packet))) => {
                    if !output_muted {
                        let from = match packet.data().data() {
                            AudioData::S2C { from, .. } | AudioData::S2CWhisper { from, .. } => tsclientlib::ClientId(*from),
                            _ => continue,
                        };
                        let _ = audio.handle_packet(from, packet);
                    }
                },
                Some(Ok(StreamItem::MessageResult(handle, result))) => match operations.remove(&handle) {
                    Some(PendingOperation::Chat(id)) => {
                        match result {
                            Ok(()) => chats.finish(&id, ChatStatus::Sent, None),
                            Err(_) => chats.finish(&id, ChatStatus::Failed, Some("message_send_failed".into())),
                        }
                        if chat_writable { persist_chat(persist_tx, storage.as_deref(), current_server.as_deref(), &chats); }
                        publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                    }
                    Some(PendingOperation::Other(code)) => if let Err(error) = result {
                        report_code(out, code, Some(error.to_string()));
                    },
                    None => {},
                },
                Some(Ok(StreamItem::FileDownload(handle, download))) => {
                    if let Some(request) = transfers.remove(&handle) {
                        let tx = media_tx.clone();
                        tokio::spawn(async move {
                            let result = if download.size == 0 || download.size > MAX_IMAGE_BYTES {
                                Err("Invalid image size".to_owned())
                            } else {
                                let mut bytes = Vec::with_capacity(download.size as usize);
                                match download.stream.take(MAX_IMAGE_BYTES + 1).read_to_end(&mut bytes).await {
                                    Ok(_) if bytes.len() as u64 == download.size && image_data_valid(&bytes) => {
                                        let path = request.local.clone();
                                        tokio::task::spawn_blocking(move || atomic_write(&path, &bytes)).await
                                            .map_err(|error| error.to_string())
                                            .and_then(|result| result.map_err(|error| error.to_string()))
                                    }
                                    Ok(_) => Err("Invalid image data".to_owned()),
                                    Err(error) => Err(error.to_string()),
                                }
                            };
                            let _ = tx.send((request, result));
                        });
                    }
                },
                Some(Ok(StreamItem::FiletransferFailed(handle, _))) => {
                    if let Some(request) = transfers.remove(&handle) {
                        media_active.remove(&request.key);
                        media_failed.insert(request.key);
                    }
                    schedule_media(&mut con, storage.as_deref(), &mut media_active, &mut media_failed, &mut transfers);
                },
                Some(Ok(StreamItem::DisconnectedTemporarily(_))) => {
                    if chats.fail_pending("message_unconfirmed_after_reconnect") {
                        if chat_writable { persist_chat(persist_tx, storage.as_deref(), current_server.as_deref(), &chats); }
                        publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                    }
                    operations.clear();
                    subscribed = false;
                    media_active.clear();
                    media_failed.clear();
                    transfers.clear();
                    audio.reset();
                    denoiser = new_denoiser(*noise_suppression);
                    out.lock().unwrap().status("reconnecting");
                },
                Some(Err(error)) => return Err(error.into()),
                None => return Ok(false),
                _ => {},
            },
            Some((request, result)) = media_rx.recv() => {
                media_active.remove(&request.key);
                if result.is_err() { media_failed.insert(request.key); }
                snapshot(&con, out, &audio, storage.as_deref());
                publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                schedule_media(&mut con, storage.as_deref(), &mut media_active, &mut media_failed, &mut transfers);
            },
            Some((request, result)) = badge_rx.recv() => {
                badge_active.remove(&request.key);
                if result.is_err() { badge_failed.insert(request.key); }
                snapshot(&con, out, &audio, storage.as_deref());
                schedule_badges(&con, storage.as_deref(), &mut badge_active, &badge_failed, &badge_tx);
            },
            received = rx.recv() => match received {
                Some(Command::Disconnect) | None => {
                    con.disconnect(DisconnectOptions::new())?;
                    let _ = tokio::time::timeout(Duration::from_secs(2), async { while con.events().next().await.is_some() {} }).await;
                    return Ok(false);
                },
                Some(Command::Shutdown) => { let _ = con.disconnect(DisconnectOptions::new()); return Ok(true); },
                Some(Command::Connect { .. }) => report_code(out, "disconnect_before_connect", None),
                Some(Command::Configure { .. }) => report_code(out, "storage_change_while_connected", None),
                Some(Command::SetChatVisible { server, conversation, token, visible }) => {
                    unread.set_visible(&server, &conversation, &token, visible);
                    out.lock().unwrap().set_unread(unread.snapshot());
                },
                Some(Command::SetAppActive { active }) => {
                    *app_active = active;
                    unread.set_app_active(active);
                    out.lock().unwrap().set_unread(unread.snapshot());
                },
                Some(Command::Join { channel, password }) => {
                    if !subscribed { report_code(out, "connection_recovering", None); continue; }
                    let state = con.get_state()?;
                    if state.clients.get(&state.own_client).is_some_and(|client| client.channel.0 == channel) { continue; }
                    let Some(me) = state.clients.get(&state.own_client) else { report_code(out, "client_state_unavailable", None); continue; };
                    let request = me.client_move(ChannelId(channel)).set_password(&password).to_packet();
                    let handle = request.send_with_result(&mut con)?;
                    operations.insert(handle, PendingOperation::Other("join_channel_failed"));
                    audio.reset(); out.lock().unwrap().audio.clear();
                },
                Some(Command::Mute { input, output }) => {
                    input_muted = input; output_muted = output;
                    if output { audio.reset(); out.lock().unwrap().audio.clear(); }
                    while pcm.try_recv().is_ok() {}
                    denoiser = new_denoiser(*noise_suppression);
                    if subscribed {
                        let update = con.get_state()?.client_update()
                            .set_input_muted(input)
                            .set_output_muted(output);
                        match update.send_with_result(&mut con) {
                            Ok(handle) => { operations.insert(handle, PendingOperation::Other("update_audio_state_failed")); },
                            Err(error) => report_code(out, "update_audio_state_failed", Some(error.to_string())),
                        }
                    }
                },
                Some(Command::SetNoiseSuppression { mode }) => {
                    if *noise_suppression != mode {
                        *noise_suppression = mode;
                        denoiser = new_denoiser(mode);
                    }
                },
                Some(Command::SendChannelMessage { request_id, channel, message }) => {
                    let target = MessageTarget::Channel;
                    match outgoing_message(&con, request_id.clone(), target, Some(channel), None, message.clone()) {
                        Ok(chat) => {
                            let id = chat.id.clone();
                            chats.messages.push(chat);
                            let send = con.get_state()?.send_message(target, &message).send_with_result(&mut con);
                            match send {
                                Ok(handle) => { operations.insert(handle, PendingOperation::Chat(id)); },
                                Err(_) => chats.finish(&id, ChatStatus::Failed, Some("message_send_failed".into())),
                            }
                            if chat_writable { persist_chat(persist_tx, storage.as_deref(), current_server.as_deref(), &chats); }
                            publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                        }
                        Err(error) => {
                            chats.messages.push(rejected_message(
                                &con,
                                request_id,
                                target,
                                Some(channel),
                                None,
                                message,
                                error.to_owned(),
                            ));
                            if chat_writable { persist_chat(persist_tx, storage.as_deref(), current_server.as_deref(), &chats); }
                            publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                            report_code(out, error, None);
                        }
                    }
                },
                Some(Command::SendPrivateMessage { request_id, client, uid, message }) => {
                    let target = MessageTarget::Client(tsclientlib::ClientId(client));
                    match outgoing_message(&con, request_id.clone(), target, None, Some(&uid), message.clone()) {
                        Ok(chat) => {
                            let id = chat.id.clone();
                            chats.messages.push(chat);
                            let send = con.get_state()?.send_message(target, &message).send_with_result(&mut con);
                            match send {
                                Ok(handle) => { operations.insert(handle, PendingOperation::Chat(id)); },
                                Err(_) => chats.finish(&id, ChatStatus::Failed, Some("message_send_failed".into())),
                            }
                            if chat_writable { persist_chat(persist_tx, storage.as_deref(), current_server.as_deref(), &chats); }
                            publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                        }
                        Err(error) => {
                            chats.messages.push(rejected_message(
                                &con,
                                request_id,
                                target,
                                None,
                                Some(&uid),
                                message,
                                error.to_owned(),
                            ));
                            if chat_writable { persist_chat(persist_tx, storage.as_deref(), current_server.as_deref(), &chats); }
                            publish_chat(out, &chats, storage.as_deref(), current_server.as_deref());
                            report_code(out, error, None);
                        }
                    }
                },
            },
            Some(samples) = pcm.recv() => {
                if !input_muted && !output_muted && con.can_send_audio() {
                    let mut encoded = [0u8; 1275];
                    let state = con.get_state()?;
                    let codec = state.clients.get(&state.own_client)
                        .and_then(|me| state.channels.get(&me.channel)).map(|channel| channel.codec);
                    let codec = match codec {
                        Some(tsclientlib::Codec::OpusVoice) => CodecType::OpusVoice,
                        Some(tsclientlib::Codec::OpusMusic) => CodecType::OpusMusic,
                        _ => { input_muted = true; report_audio_muted(out, "legacy_codec", None); continue; }
                    };
                    let denoised = denoiser.as_mut().and_then(|state| {
                        catch_unwind(AssertUnwindSafe(|| denoise_packet(&samples, state)))
                            .ok()
                            .flatten()
                    });
                    if denoiser.is_some() && denoised.is_none() {
                        eprintln!("RNNoise processing failed; sending unprocessed microphone audio");
                        denoiser = None;
                    }
                    let input = denoised.as_ref().map_or(samples.as_slice(), |packet| packet.as_slice());
                    let len = encoder.encode(input, &mut encoded)?;
                    con.send_audio(OutAudio::new(&AudioData::C2S { id:0, codec, data:&encoded[..len] }))?;
                }
            },
            _ = clock.tick() => {
                if subscribed {
                    if !output_muted {
                        let mut samples = vec![0f32; 1920];
                        audio.fill_buffer(&mut samples);
                        for value in &mut samples { *value = value.clamp(-1., 1.); }
                        let mut output = out.lock().unwrap();
                        // ponytail: one active server; retain at most 100ms when the UI stalls.
                        if output.audio.len() >= 5 { output.audio.pop_front(); }
                        output.audio.push_back(samples);
                    }
                    ticks += 1;
                    if ticks % 10 == 0 { snapshot(&con, out, &audio, storage.as_deref()); }
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
        let worker_output = output.clone();
        let persistence_output = output.clone();
        let (persist_tx, persist_rx) = std_mpsc::channel::<PersistJob>();
        std::thread::spawn(move || {
            while let Ok(first) = persist_rx.recv() {
                let mut latest = HashMap::from([(first.path, first.data)]);
                while let Ok(job) = persist_rx.try_recv() {
                    latest.insert(job.path, job.data);
                }
                for (path, data) in latest {
                    if let Err(error) = atomic_write(&path, &data) {
                        report_code(
                            &persistence_output,
                            "chat_history_save_failed",
                            Some(error.to_string()),
                        );
                    }
                }
            }
        });
        std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();
            runtime.block_on(async {
                let mut storage: Option<PathBuf> = None;
                let mut app_active = false;
                let mut noise_suppression = NoiseSuppressionMode::default();
                while let Some(request) = rx.recv().await {
                    match request {
                        Command::Shutdown => break,
                        Command::SetAppActive { active } => app_active = active,
                        Command::SetNoiseSuppression { mode } => noise_suppression = mode,
                        Command::Configure { storage: value } => {
                            storage = Some(PathBuf::from(value))
                        }
                        request @ Command::Connect { .. } => {
                            match session(
                                request,
                                &mut rx,
                                &mut pcm_rx,
                                &worker_output,
                                storage.clone(),
                                &persist_tx,
                                &mut app_active,
                                &mut noise_suppression,
                            )
                            .await
                            {
                                Ok(true) => break,
                                Err(error) => report(&worker_output, error),
                                _ => {}
                            }
                            worker_output.lock().unwrap().status("disconnected");
                            worker_output.lock().unwrap().set_chats(json!([]));
                            worker_output
                                .lock()
                                .unwrap()
                                .set_unread(UnreadState::default().snapshot());
                        }
                        _ => {}
                    }
                }
            });
        });
        Self { tx, pcm, output }
    }
    pub fn send(&self, json: &str) -> Result<(), String> {
        let request: Command = serde_json::from_str(json).map_err(|error| error.to_string())?;
        request.validate().map_err(str::to_owned)?;
        self.tx.try_send(request).map_err(|error| error.to_string())
    }
    pub fn poll(&self) -> Value {
        let mut out = self.output.lock().unwrap();
        let events: Vec<_> = out.events.drain(..).collect();
        let chats = out.chat_snapshot.take().unwrap_or(Value::Null);
        json!({"snapshot":out.snapshot,"events":events,"chats":chats,"unread":out.unread})
    }
}
impl Default for Bridge {
    fn default() -> Self {
        Self::new()
    }
}
impl Drop for Bridge {
    fn drop(&mut self) {
        let _ = self.tx.try_send(Command::Shutdown);
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
    match (*handle)
        .pcm
        .try_send(std::slice::from_raw_parts(samples, len).to_vec())
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
    if handle.is_null() || samples.is_null() || capacity < 1920 {
        return 0;
    }
    let mut out = (*handle).output.lock().unwrap();
    if let Some(frame) = out.audio.pop_front() {
        std::ptr::copy_nonoverlapping(frame.as_ptr(), samples, frame.len());
        frame.len()
    } else {
        0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
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
        assert_eq!(actual, expected);
        assert_ne!(actual, samples);
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
