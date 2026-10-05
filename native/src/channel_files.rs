//! Current-channel UI policy above tsclientlib's unchanged, general file API.
use crate::{server_id, Command, Output, PendingOperation};
use serde::Serialize;
use serde_json::json;
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    sync::mpsc,
    time::Instant,
};
use tsclientlib::{ChannelId, Connection, FiletransferHandle, MessageHandle, OutCommandExt};

use tsclientlib::messages::s2c::InMessage;

const TIMEOUT: Duration = Duration::from_secs(30);

pub fn valid_name(name: &str) -> bool {
    !name.is_empty()
        && name != "."
        && name != ".."
        && name.len() <= 255
        && !name
            .chars()
            .any(|c| c == '/' || c == '\\' || c.is_control())
}
pub fn valid_path(path: &str) -> bool {
    path == "/"
        || (path.starts_with('/') && path.len() <= 4096 && path[1..].split('/').all(valid_name))
}
fn child(path: &str, name: &str) -> String {
    format!(
        "{}{name}",
        if path == "/" {
            "/".into()
        } else {
            format!("{path}/")
        }
    )
}
fn parent(path: &str) -> &str {
    path.rsplit_once('/')
        .map(|(p, _)| if p.is_empty() { "/" } else { p })
        .unwrap_or("/")
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
struct Context {
    server: String,
    channel: u64,
    channel_name: String,
}
fn context(con: &Connection) -> Option<Context> {
    let state = con.get_state().ok()?;
    let own = state.clients.get(&state.own_client)?;
    let channel = state.channels.get(&own.channel)?;
    Some(Context {
        server: server_id(con)?,
        channel: channel.id.0,
        channel_name: channel.name.clone(),
    })
}
fn same_channel(a: &Context, b: &Context) -> bool {
    a.server == b.server && a.channel == b.channel
}

fn command_error(error: &tsclientlib::CommandError) -> String {
    use tsclientlib::TsError;
    match error.error {
        TsError::FileAlreadyExists => "files_exists".into(),
        TsError::FileInvalidPermissions
        | TsError::PermissionsClientInsufficient
        | TsError::Permissions => "files_permission_denied".into(),
        _ if error.missing_permission.is_some() => "files_permission_denied".into(),
        _ => format!("{:?} (0x{:04x})", error.error, error.error as u32),
    }
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
struct Entry {
    name: String,
    size: u64,
    timestamp: i64,
    directory: bool,
    icon: &'static str,
    local_path: Option<String>,
}
fn icon(name: &str) -> &'static str {
    match Path::new(name)
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "pdf" | "txt" | "md" | "rtf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "csv"
        | "json" | "xml" | "log" => "file-text",
        "jpg" | "jpeg" | "png" | "gif" | "webp" | "heic" | "svg" | "bmp" | "tiff" => "file-image",
        "mp3" | "wav" | "ogg" | "flac" | "m4a" | "aac" | "opus" => "file-audio",
        "zip" | "7z" | "rar" | "tar" | "gz" | "bz2" | "xz" => "file-archive",
        _ => "file",
    }
}
fn sort(entries: &mut [Entry], newest: bool) {
    entries.sort_by(|a, b| {
        b.directory
            .cmp(&a.directory)
            .then_with(|| {
                if newest {
                    b.timestamp.cmp(&a.timestamp)
                } else {
                    std::cmp::Ordering::Equal
                }
            })
            .then_with(|| a.name.to_lowercase().cmp(&b.name.to_lowercase()))
            .then_with(|| a.name.cmp(&b.name))
    });
}
fn destination(root: &Path, ctx: &Context, path: &str, entry: &Entry) -> PathBuf {
    use md5::{Digest, Md5};
    let key = format!(
        "{path}\n{}\n{}\n{}",
        entry.name, entry.size, entry.timestamp
    );
    root.join("channel-files")
        .join(format!("{:x}", Md5::digest(ctx.server.as_bytes())))
        .join(ctx.channel.to_string())
        .join(format!("{:x}", Md5::digest(key.as_bytes())))
        .join("file")
        .join(&entry.name)
}

struct Listing {
    handle: MessageHandle,
    ctx: Context,
    path: String,
    generation: u64,
    entries: HashMap<String, Entry>,
    invalid: bool,
    finished: bool,
    acknowledged: bool,
    deadline: Instant,
    expired: bool,
}
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct Transfer {
    id: u64,
    server: String,
    channel: u64,
    path: String,
    name: String,
    upload: bool,
    size: u64,
    transferred: u64,
    status: &'static str,
    error: Option<String>,
    local_path: Option<String>,
    #[serde(skip)]
    source: Option<PathBuf>,
    #[serde(skip)]
    entry: Option<Entry>,
    #[serde(skip)]
    deadline: Option<Instant>,
}
fn task_matches(task: &Transfer, entry: &Entry) -> bool {
    task.entry.as_ref().is_some_and(|old| {
        old.name == entry.name
            && old.size == entry.size
            && old.timestamp == entry.timestamp
            && !entry.directory
    })
}
struct Job {
    task: tokio::task::JoinHandle<()>,
    partial: Option<PathBuf>,
}
// Abort on session loss; a dropped future must never promote a partial download.
impl Drop for Job {
    fn drop(&mut self) {
        self.task.abort();
        if let Some(path) = &self.partial {
            let _ = std::fs::remove_file(path);
        }
    }
}
pub enum Update {
    Progress(u64, u64),
    Finished(u64, Result<Option<String>, String>),
    Cache(u64, bool, CacheResult),
}
#[derive(Default, Serialize)]
#[serde(rename_all = "camelCase")]
struct Cache {
    bytes: Option<u64>,
    items: u64,
    status: &'static str,
    error: Option<String>,
}
pub struct CacheResult {
    bytes: Option<u64>,
    items: u64,
    removed: u64,
    error: Option<String>,
}
// Only the two app-owned file directories are visited. A symlink is never
// followed, even when it replaces one of those directories.
fn visit_cache(path: &Path, clear: bool, bytes: &mut u64, items: &mut u64) -> std::io::Result<()> {
    let metadata = match std::fs::symlink_metadata(path) {
        Ok(metadata) => metadata,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(error) => return Err(error),
    };
    if metadata.is_dir() {
        for entry in std::fs::read_dir(path)? {
            visit_cache(&entry?.path(), clear, bytes, items)?;
        }
        if clear {
            std::fs::remove_dir(path)?;
        }
    } else {
        if clear {
            std::fs::remove_file(path)?;
        }
        if metadata.is_file() {
            *bytes = bytes.saturating_add(metadata.len());
        }
        *items = items.saturating_add(1);
    }
    Ok(())
}
fn cache_files(root: &Path, clear: bool) -> CacheResult {
    let mut removed = 0;
    let mut items = 0;
    let mut error = None;
    for name in ["channel-files", "file-uploads"] {
        if let Err(failure) = visit_cache(&root.join(name), clear, &mut removed, &mut items) {
            error.get_or_insert_with(|| failure.to_string());
        }
    }
    let bytes = if clear {
        let remaining = cache_files(root, false);
        items = remaining.items;
        if let Some(failure) = remaining.error {
            error.get_or_insert(failure);
        }
        remaining.bytes
    } else if error.is_none() {
        Some(removed)
    } else {
        None
    };
    CacheResult {
        bytes,
        items,
        removed: if clear { removed } else { 0 },
        error,
    }
}
#[derive(Default)]
pub struct Files {
    ctx: Option<Context>,
    open: bool,
    path: String,
    newest: bool,
    generation: u64,
    status: &'static str,
    error: Option<String>,
    entries: Vec<Entry>,
    listing: Option<Listing>,
    queued: bool,
    transfers: Vec<Transfer>,
    handles: HashMap<FiletransferHandle, u64>,
    jobs: HashMap<u64, Job>,
    sequence: u64,
    passwords: HashMap<u64, String>,
    password_server: Option<String>,
    cache: Cache,
}
impl Files {
    fn busy(&self) -> bool {
        self.transfers.iter().any(|task| task.status == "running")
    }
    pub fn cache_command(
        &mut self,
        request: u64,
        clear: bool,
        root: Option<&Path>,
        tx: &mpsc::UnboundedSender<Update>,
        out: &Arc<Mutex<Output>>,
    ) {
        let error = if matches!(self.cache.status, "loading" | "clearing") || (clear && self.busy())
        {
            Some("files_cache_busy")
        } else if root.is_none() {
            Some("files_storage_failed")
        } else {
            None
        };
        if let Some(error) = error {
            out.lock()
                .unwrap()
                .event(json!({"type":"file_cache_result","requestId":request,"error":error}));
            return;
        }
        self.cache.status = if clear { "clearing" } else { "loading" };
        self.cache.error = None;
        self.publish(out);
        let root = root.unwrap().to_owned();
        let tx = tx.clone();
        tokio::spawn(async move {
            let result = tokio::task::spawn_blocking(move || cache_files(&root, clear))
                .await
                .unwrap_or_else(|error| CacheResult {
                    bytes: None,
                    items: 0,
                    removed: 0,
                    error: Some(error.to_string()),
                });
            let _ = tx.send(Update::Cache(request, clear, result));
        });
    }
    pub fn publish(&self, out: &Arc<Mutex<Output>>) {
        let transfers: Vec<_> = self
            .transfers
            .iter()
            .filter(|t| {
                self.ctx
                    .as_ref()
                    .is_some_and(|c| c.server == t.server && c.channel == t.channel)
                    && (t.upload
                        || self.status != "ready"
                        || self.path != t.path
                        || self.entries.iter().any(|e| task_matches(t, e)))
            })
            .collect();
        let value = json!({"open":self.open,"server":self.ctx.as_ref().map(|c| &c.server),"channel":self.ctx.as_ref().map(|c| c.channel),
            "channelName":self.ctx.as_ref().map(|c| &c.channel_name),"path":if self.path.is_empty(){"/"}else{&self.path},
            "sort":if self.newest{"newest"}else{"name"},"status":if self.status.is_empty(){"idle"}else{self.status},"error":self.error,"entries":self.entries,"transfers":transfers});
        let mut out = out.lock().unwrap();
        if out.channel_files != value {
            out.channel_files = value;
            out.notify();
        }
        let cache = json!({"bytes":self.cache.bytes,"items":self.cache.items,"status":if self.cache.status.is_empty(){"idle"}else{self.cache.status},"error":self.cache.error,"busy":self.busy()});
        if out.file_cache != cache {
            out.file_cache = cache;
            out.notify();
        }
    }
    pub fn observe(&mut self, con: &Connection, out: &Arc<Mutex<Output>>) {
        self.apply_context(context(con), out);
    }
    fn apply_context(&mut self, next: Option<Context>, out: &Arc<Mutex<Output>>) {
        if let Some(next) = &next {
            if self.password_server.as_ref() != Some(&next.server) {
                self.passwords.clear();
                self.password_server = Some(next.server.clone());
            }
        }
        if self.ctx == next {
            return;
        }
        if self
            .ctx
            .as_ref()
            .zip(next.as_ref())
            .is_some_and(|(a, b)| same_channel(a, b))
        {
            self.ctx = next;
        } else if self.ctx != next {
            self.ctx = next;
            self.open = false;
            self.path = "/".into();
            self.entries.clear();
            self.status = "idle";
            self.error = None;
            self.queued = false;
            self.generation += 1;
        }
        self.publish(out);
    }
    pub fn join_password(&mut self, channel: u64, password: String) {
        self.passwords.insert(channel, password);
    }
    pub fn disconnected(&mut self, out: &Arc<Mutex<Output>>) {
        self.jobs.clear();
        self.handles.clear();
        self.listing = None;
        self.queued = false;
        for t in &mut self.transfers {
            if t.status == "running" {
                t.status = "failed";
                t.error = Some("files_disconnected".into());
                t.deadline = None;
            }
        }
        // Retain failed task records for explicit retry after reconnecting to the same channel.
        self.open = false;
        self.entries.clear();
        self.status = "idle";
        self.generation += 1;
        self.publish(out);
        self.ctx = None;
    }
    fn load(&mut self) {
        self.generation += 1;
        self.status = "loading";
        self.error = None;
        self.entries.clear();
        self.queued = true;
    }
    pub fn command(
        &mut self,
        command: Command,
        con: &mut Connection,
        root: Option<&Path>,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
        out: &Arc<Mutex<Output>>,
    ) {
        self.observe(con, out);
        let source = if let Command::FilesUpload { source, .. } = &command {
            Some(PathBuf::from(source))
        } else {
            None
        };
        let result = self.execute(command, con, root);
        if let Err(error) = result {
            self.reject(source.as_deref(), root, error);
        }
        self.pump(con, operations);
        self.publish(out);
    }
    fn reject(&mut self, source: Option<&Path>, root: Option<&Path>, error: String) {
        if let (Some(source), Some(root)) = (source, root) {
            if let (Ok(source), Ok(staging)) = (
                std::fs::canonicalize(source),
                std::fs::canonicalize(root.join("file-uploads")),
            ) {
                if source.starts_with(staging)
                    && !self
                        .transfers
                        .iter()
                        .any(|t| t.source.as_ref() == Some(&source))
                {
                    let _ = std::fs::remove_file(source);
                }
            }
        }
        self.error = Some(error);
    }
    pub fn offline(&mut self, command: &Command, root: Option<&Path>, out: &Arc<Mutex<Output>>) {
        let source = match command {
            Command::FilesUpload { source, .. } => Some(Path::new(source)),
            _ => None,
        };
        self.reject(source, root, "files_disconnected".into());
        self.publish(out);
    }
    fn execute(
        &mut self,
        command: Command,
        con: &mut Connection,
        root: Option<&Path>,
    ) -> Result<(), String> {
        match command {
            Command::FilesOpen { server, channel } => {
                let ctx = self.ctx.as_ref().ok_or("files_disconnected")?;
                if ctx.server != server || ctx.channel != channel {
                    return Err("files_channel_changed".into());
                }
                self.open = true;
                self.path = "/".into();
                self.load();
            }
            Command::FilesBack {
                server,
                channel,
                directory,
            } => {
                self.check(&server, channel, &directory)?;
                if self.path == "/" {
                    self.open = false;
                    self.entries.clear();
                    self.queued = false;
                    self.generation += 1;
                } else {
                    self.path = parent(&self.path).into();
                    self.load();
                }
            }
            Command::FilesList {
                server,
                channel,
                directory,
                path,
            } => {
                self.check(&server, channel, &directory)?;
                if !self.open {
                    return Err("files_channel_changed".into());
                }
                if path != self.path
                    && !self
                        .entries
                        .iter()
                        .any(|e| e.directory && child(&self.path, &e.name) == path)
                {
                    return Err("files_invalid_path".into());
                }
                self.path = path;
                self.load();
            }
            Command::FilesSort {
                server,
                channel,
                directory,
                newest,
            } => {
                self.check(&server, channel, &directory)?;
                self.newest = newest;
                sort(&mut self.entries, newest);
            }
            Command::FilesDownload {
                server,
                channel,
                path,
                name,
            } => {
                self.check(&server, channel, &path)?;
                let entry = self
                    .entries
                    .iter()
                    .find(|e| e.name == name && !e.directory)
                    .cloned()
                    .ok_or("files_invalid_path")?;
                let ctx = self.ctx.clone().unwrap();
                let local = destination(root.ok_or("files_storage_failed")?, &ctx, &path, &entry);
                self.start(con, path, name, Some(entry), None, local)?;
            }
            Command::FilesUpload {
                server,
                channel,
                path,
                name,
                source,
            } => {
                self.check(&server, channel, &path)?;
                if self.status != "ready" {
                    return Err("files_load_first".into());
                }
                if self.entries.iter().any(|e| e.name == name) {
                    return Err("files_exists".into());
                }
                let root = root.ok_or("files_storage_failed")?;
                let staging = std::fs::canonicalize(root.join("file-uploads"))
                    .map_err(|_| "files_storage_failed")?;
                let source = std::fs::canonicalize(source).map_err(|_| "files_storage_failed")?;
                if !source.starts_with(staging) || !source.is_file() {
                    return Err("files_invalid_path".into());
                }
                self.start(con, path, name, None, Some(source), PathBuf::new())?;
            }
            Command::FilesRetry { id } => {
                let task = self
                    .transfers
                    .iter()
                    .find(|t| t.id == id && t.status == "failed")
                    .cloned()
                    .ok_or("files_invalid_path")?;
                self.check(&task.server, task.channel, &task.path)?;
                if task.upload && self.entries.iter().any(|e| e.name == task.name) {
                    return Err("files_exists".into());
                }
                self.start(
                    con,
                    task.path,
                    task.name,
                    task.entry,
                    task.source,
                    task.local_path.map(PathBuf::from).unwrap_or_default(),
                )?;
                self.transfers.retain(|t| t.id != id);
            }
            _ => unreachable!(),
        }
        Ok(())
    }
    fn check(&self, server: &str, channel: u64, path: &str) -> Result<(), String> {
        if !self.open
            || !self
                .ctx
                .as_ref()
                .is_some_and(|c| c.server == server && c.channel == channel)
            || path != self.path
        {
            Err("files_channel_changed".into())
        } else {
            Ok(())
        }
    }
    fn start(
        &mut self,
        con: &mut Connection,
        path: String,
        name: String,
        entry: Option<Entry>,
        source: Option<PathBuf>,
        local: PathBuf,
    ) -> Result<(), String> {
        if self.cache.status == "clearing" {
            return Err("files_cache_busy".into());
        }
        let ctx = self.ctx.clone().ok_or("files_disconnected")?;
        if self
            .transfers
            .iter()
            .filter(|t| t.status == "running")
            .count()
            >= 4
        {
            return Err("files_busy".into());
        }
        if self.transfers.iter().any(|t| {
            t.server == ctx.server
                && t.channel == ctx.channel
                && t.path == path
                && t.name == name
                && t.status == "running"
        }) {
            return Err("files_busy".into());
        }
        let upload = source.is_some();
        let size = if let Some(source) = &source {
            std::fs::metadata(source)
                .map_err(|_| "files_storage_failed")?
                .len()
        } else {
            entry.as_ref().unwrap().size
        };
        let password = self.passwords.get(&ctx.channel).map(String::as_str);
        let remote = child(&path, &name);
        let handle = if upload {
            con.upload_file(
                ChannelId(ctx.channel),
                &remote,
                password,
                size,
                false,
                false,
            )
        } else {
            con.download_file(ChannelId(ctx.channel), &remote, password, None)
        }
        .map_err(|e| e.to_string())?;
        self.sequence += 1;
        self.handles.insert(handle, self.sequence);
        self.transfers.retain(|t| {
            !(t.server == ctx.server
                && t.channel == ctx.channel
                && t.path == path
                && t.name == name
                && t.upload == upload)
        });
        self.transfers.push(Transfer {
            id: self.sequence,
            server: ctx.server,
            channel: ctx.channel,
            path,
            name,
            upload,
            size,
            transferred: 0,
            status: "running",
            error: None,
            local_path: (!upload).then(|| local.to_string_lossy().into()),
            source,
            entry,
            deadline: Some(Instant::now() + TIMEOUT),
        });
        Ok(())
    }
    pub fn pump(
        &mut self,
        con: &mut Connection,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
    ) {
        if self.listing.is_some() || !self.queued || !self.open {
            return;
        }
        self.queued = false;
        let Some(ctx) = self.ctx.clone() else { return };
        let result = (|| {
            let state = con.get_state().map_err(|e| e.to_string())?;
            let channel = state
                .channels
                .get(&ChannelId(ctx.channel))
                .ok_or("files_disconnected")?;
            channel
                .file_list(
                    self.passwords
                        .get(&ctx.channel)
                        .map(String::as_str)
                        .unwrap_or(""),
                    &self.path,
                )
                .send_with_result(con)
                .map_err(|e| e.to_string())
        })();
        match result {
            Ok(handle) => {
                operations.insert(handle, PendingOperation::FilesList);
                self.listing = Some(Listing {
                    handle,
                    ctx,
                    path: self.path.clone(),
                    generation: self.generation,
                    entries: HashMap::new(),
                    invalid: false,
                    finished: false,
                    acknowledged: false,
                    deadline: Instant::now() + TIMEOUT,
                    expired: false,
                });
            }
            Err(e) => {
                self.status = "failed";
                self.error = Some(e.to_string());
            }
        }
    }
    fn listing_current(&self, l: &Listing) -> bool {
        self.open
            && l.generation == self.generation
            && self.ctx.as_ref().is_some_and(|c| same_channel(c, &l.ctx))
            && l.path == self.path
    }
    pub fn message(&mut self, msg: &InMessage, root: Option<&Path>, out: &Arc<Mutex<Output>>) {
        if let Some(l) = &mut self.listing {
            match msg {
                InMessage::FileList(items) => {
                    for item in items.iter() {
                        if item.channel_id.0 == l.ctx.channel
                            && item.path == l.path
                            && !valid_name(&item.name)
                        {
                            l.invalid = true;
                        }
                        if item.channel_id.0 == l.ctx.channel
                            && item.path == l.path
                            && valid_name(&item.name)
                        {
                            let mut entry = Entry {
                                name: item.name.clone(),
                                size: item.size,
                                timestamp: item.date_time.unix_timestamp(),
                                directory: !item.is_file,
                                icon: if item.is_file {
                                    icon(&item.name)
                                } else {
                                    "folder"
                                },
                                local_path: None,
                            };
                            if item.is_file {
                                if let Some(root) = root {
                                    let local = destination(root, &l.ctx, &l.path, &entry);
                                    if std::fs::metadata(&local)
                                        .is_ok_and(|m| m.is_file() && m.len() == entry.size)
                                    {
                                        entry.local_path = Some(local.to_string_lossy().into());
                                    }
                                }
                            }
                            l.entries.insert(entry.name.clone(), entry);
                        }
                    }
                }
                InMessage::FileListFinished(items)
                    if items
                        .iter()
                        .any(|i| i.channel_id.0 == l.ctx.channel && i.path == l.path) =>
                {
                    l.finished = true;
                }
                _ => {}
            }
        }
        self.finish_list();
        self.publish(out);
    }
    pub fn list_result(
        &mut self,
        handle: MessageHandle,
        result: Result<(), tsclientlib::CommandError>,
        out: &Arc<Mutex<Output>>,
    ) {
        if self.listing.as_ref().is_none_or(|l| l.handle != handle) {
            return;
        }
        match result {
            Ok(()) => self.listing.as_mut().unwrap().acknowledged = true,
            // Servers also report DatabaseEmptyResult for an empty ftgetfilelist.
            // Accept it only for this list request, with no entries or permission failure.
            Err(e)
                if matches!(
                    e.error,
                    tsclientlib::TsError::FileNoFilesAvailable
                        | tsclientlib::TsError::DatabaseEmptyResult
                ) && e.missing_permission.is_none()
                    && self
                        .listing
                        .as_ref()
                        .is_some_and(|l| l.entries.is_empty() && !l.invalid) =>
            {
                let l = self.listing.as_mut().unwrap();
                l.entries.clear();
                l.finished = true;
                l.acknowledged = true;
            }
            Err(e) => {
                let l = self.listing.take().unwrap();
                if self.listing_current(&l) {
                    self.status = "failed";
                    self.error = Some(command_error(&e));
                }
            }
        }
        self.finish_list();
        self.publish(out);
    }
    fn finish_list(&mut self) {
        if self
            .listing
            .as_ref()
            .is_some_and(|l| l.finished && l.acknowledged)
        {
            let l = self.listing.take().unwrap();
            if self.listing_current(&l) && !l.expired {
                if l.invalid {
                    self.status = "failed";
                    self.error = Some("files_invalid_path".into());
                    return;
                }
                self.entries = l.entries.into_values().collect();
                sort(&mut self.entries, self.newest);
                self.status = "ready";
                self.error = None;
            }
        }
    }
    pub fn tick(
        &mut self,
        con: &mut Connection,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
        out: &Arc<Mutex<Output>>,
    ) {
        if self
            .listing
            .as_ref()
            .is_some_and(|l| !l.expired && Instant::now() >= l.deadline)
        {
            let current = self.listing_current(self.listing.as_ref().unwrap());
            self.listing.as_mut().unwrap().expired = true;
            if current {
                self.status = "failed";
                self.error = Some("files_timeout".into());
                self.publish(out);
            }
            // Finished has no request ID. Keep the barrier until it drains or reconnects;
            // reusing the same cid/path after a timeout would accept an old response.
        }
        let expired: Vec<_> = self
            .transfers
            .iter()
            .filter(|t| t.status == "running" && t.deadline.is_some_and(|d| Instant::now() >= d))
            .map(|t| t.id)
            .collect();
        for id in expired {
            self.update(Update::Finished(id, Err("files_timeout".into())), out);
        }
        if self.queued
            && self.listing.as_ref().is_some_and(|l| l.expired)
            && self.status != "failed"
        {
            self.status = "failed";
            self.error = Some("files_timeout".into());
            self.publish(out);
        }
        let queued = self.queued && self.listing.is_none();
        self.pump(con, operations);
        if queued {
            self.publish(out);
        }
    }
    pub fn owns(&self, handle: FiletransferHandle) -> bool {
        self.handles.contains_key(&handle)
    }
    pub fn failed(
        &mut self,
        handle: FiletransferHandle,
        error: tsclientlib::Error,
        out: &Arc<Mutex<Output>>,
    ) {
        if matches!(&error, tsclientlib::Error::CommandError(e) if e.error == tsclientlib::TsError::FileTransferComplete)
        {
            return;
        }
        let error = match &error {
            tsclientlib::Error::CommandError(e) => command_error(e),
            _ => format!("{error:?}"),
        };
        if let Some(id) = self.handles.remove(&handle) {
            self.update(Update::Finished(id, Err(error)), out);
        }
    }
    pub fn download(
        &mut self,
        handle: FiletransferHandle,
        download: tsclientlib::FileDownloadResult,
        tx: &mpsc::UnboundedSender<Update>,
        out: &Arc<Mutex<Output>>,
    ) {
        let Some(&id) = self.handles.get(&handle) else {
            return;
        };
        if self.jobs.contains_key(&id) {
            return;
        }
        let task = self.transfers.iter_mut().find(|t| t.id == id).unwrap();
        task.deadline = None;
        if download.size != task.size {
            self.update(Update::Finished(id, Err("files_changed".into())), out);
            return;
        }
        let local = PathBuf::from(task.local_path.as_ref().unwrap());
        let partial = local
            .parent()
            .unwrap()
            .parent()
            .unwrap()
            .join(format!(".partial-{id}-{}", crate::received_id()));
        let work_partial = partial.clone();
        let tx = tx.clone();
        let worker = tokio::spawn(async move {
            let result = receive(download, &local, &work_partial, id, &tx)
                .await
                .map(|()| Some(work_partial.to_string_lossy().into()));
            if result.is_err() {
                let _ = tokio::fs::remove_file(&work_partial).await;
            }
            let _ = tx.send(Update::Finished(id, result));
        });
        self.jobs.insert(
            id,
            Job {
                task: worker,
                partial: Some(partial),
            },
        );
    }
    pub fn upload(
        &mut self,
        handle: FiletransferHandle,
        transfer: tsclientlib::FileUploadResult,
        tx: &mpsc::UnboundedSender<Update>,
        out: &Arc<Mutex<Output>>,
    ) {
        let Some(&id) = self.handles.get(&handle) else {
            return;
        };
        if self.jobs.contains_key(&id) {
            return;
        }
        let task = self.transfers.iter_mut().find(|t| t.id == id).unwrap();
        task.deadline = None;
        let source = task.source.clone().unwrap();
        let size = task.size;
        let tx = tx.clone();
        let worker = tokio::spawn(async move {
            let result = send(transfer, &source, size, id, &tx).await.map(|()| None);
            let _ = tx.send(Update::Finished(id, result));
        });
        self.jobs.insert(
            id,
            Job {
                task: worker,
                partial: None,
            },
        );
        self.publish(out);
    }
    pub fn update(&mut self, update: Update, out: &Arc<Mutex<Output>>) {
        match update {
            Update::Cache(request, clear, result) => {
                self.cache.bytes = result.bytes;
                self.cache.items = result.items;
                self.cache.status = if result.error.is_some() {
                    "failed"
                } else {
                    "ready"
                };
                self.cache.error = result.error.clone();
                if clear {
                    for entry in &mut self.entries {
                        if entry.local_path.as_ref().is_some_and(|path| {
                            !std::fs::metadata(path).is_ok_and(|metadata| {
                                metadata.is_file() && metadata.len() == entry.size
                            })
                        }) {
                            entry.local_path = None;
                        }
                    }
                    self.transfers.retain(|task| {
                        if task.upload {
                            task.source.as_ref().is_some_and(|source| source.is_file())
                        } else {
                            task.local_path
                                .as_ref()
                                .is_some_and(|path| Path::new(path).is_file())
                        }
                    });
                }
                out.lock().unwrap().event(json!({"type":"file_cache_result","requestId":request,"clear":clear,"removedBytes":result.removed,"error":result.error}));
            }
            Update::Progress(id, bytes) => {
                if let Some(task) = self
                    .transfers
                    .iter_mut()
                    .find(|t| t.id == id && t.status == "running")
                {
                    task.transferred = bytes;
                }
            }
            Update::Finished(id, result) => {
                self.handles.retain(|_, v| *v != id);
                let result = result.and_then(|partial| {
                    if let Some(partial) = partial {
                        let task = self
                            .transfers
                            .iter()
                            .find(|t| t.id == id && t.status == "running")
                            .ok_or("files_disconnected")?;
                        let local = task.local_path.clone().ok_or("files_storage_failed")?;
                        std::fs::rename(partial, &local).map_err(|e| e.to_string())?;
                        Ok(Some(local))
                    } else {
                        Ok(None)
                    }
                });
                self.jobs.remove(&id);
                if let Some(task) = self
                    .transfers
                    .iter_mut()
                    .find(|t| t.id == id && t.status == "running")
                {
                    task.deadline = None;
                    match result {
                        Ok(local) => {
                            task.status = "complete";
                            task.transferred = task.size;
                            if task.upload {
                                if let Some(source) = task.source.take() {
                                    let _ = std::fs::remove_file(source);
                                }
                            } else {
                                task.local_path = local.clone();
                                if let Some(entry) = self.entries.iter_mut().find(|e| {
                                    task_matches(task, e)
                                        && self.ctx.as_ref().is_some_and(|c| {
                                            c.server == task.server && c.channel == task.channel
                                        })
                                        && self.path == task.path
                                }) {
                                    entry.local_path = local;
                                }
                            }
                            if task.upload
                                && self.open
                                && self.path == task.path
                                && self.ctx.as_ref().is_some_and(|c| {
                                    c.server == task.server && c.channel == task.channel
                                })
                            {
                                self.load();
                            }
                        }
                        Err(e) => {
                            task.status = "failed";
                            task.error = Some(e);
                        }
                    }
                }
            }
        }
        self.publish(out);
    }
}
async fn receive(
    mut download: tsclientlib::FileDownloadResult,
    local: &Path,
    partial: &Path,
    id: u64,
    tx: &mpsc::UnboundedSender<Update>,
) -> Result<(), String> {
    tokio::fs::create_dir_all(local.parent().unwrap())
        .await
        .map_err(|e| e.to_string())?;
    let mut file = tokio::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(partial)
        .await
        .map_err(|e| e.to_string())?;
    let mut buffer = [0u8; 64 * 1024];
    let mut total = 0;
    let mut emitted = Instant::now();
    while total < download.size {
        let count = (download.size - total).min(buffer.len() as u64) as usize;
        let n = tokio::time::timeout(TIMEOUT, download.stream.read(&mut buffer[..count]))
            .await
            .map_err(|_| "files_timeout")?
            .map_err(|e| e.to_string())?;
        if n == 0 {
            return Err("files_incomplete".into());
        }
        file.write_all(&buffer[..n])
            .await
            .map_err(|e| e.to_string())?;
        total += n as u64;
        if emitted.elapsed() >= Duration::from_millis(100) {
            let _ = tx.send(Update::Progress(id, total));
            emitted = Instant::now();
        }
    }
    file.sync_all().await.map_err(|e| e.to_string())?;
    drop(file);
    Ok(())
}
async fn send(
    mut transfer: tsclientlib::FileUploadResult,
    source: &Path,
    size: u64,
    id: u64,
    tx: &mpsc::UnboundedSender<Update>,
) -> Result<(), String> {
    if transfer.seek_position != 0 {
        return Err("files_invalid_offset".into());
    }
    let mut file = tokio::fs::File::open(source)
        .await
        .map_err(|e| e.to_string())?;
    let mut buffer = [0u8; 64 * 1024];
    let mut total = 0;
    let mut emitted = Instant::now();
    while total < size {
        let count = (size - total).min(buffer.len() as u64) as usize;
        let n = file
            .read(&mut buffer[..count])
            .await
            .map_err(|e| e.to_string())?;
        if n == 0 {
            return Err("files_incomplete".into());
        }
        tokio::time::timeout(TIMEOUT, transfer.stream.write_all(&buffer[..n]))
            .await
            .map_err(|_| "files_timeout")?
            .map_err(|e| e.to_string())?;
        total += n as u64;
        if emitted.elapsed() >= Duration::from_millis(100) {
            let _ = tx.send(Update::Progress(id, total));
            emitted = Instant::now();
        }
    }
    // TeamSpeak closes the stream after receiving the declared size, including zero.
    let mut response = [0u8; 1];
    let n = tokio::time::timeout(TIMEOUT, transfer.stream.read(&mut response))
        .await
        .map_err(|_| "files_timeout")?
        .map_err(|e| e.to_string())?;
    if n != 0 {
        return Err("files_unexpected_response".into());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cache_clear_only_removes_owned_file_data_and_never_follows_links() {
        let root = std::env::temp_dir().join(format!("file-cache-{}", crate::received_id()));
        let outside = root.join("originals");
        for directory in [
            "channel-files/server/8/version/file",
            "file-uploads",
            "audio-models-v1",
            "avatars",
            "originals",
        ] {
            std::fs::create_dir_all(root.join(directory)).unwrap();
        }
        std::fs::write(
            root.join("channel-files/server/8/version/file/中文.mp3"),
            b"music",
        )
        .unwrap();
        std::fs::write(root.join("channel-files/zero.txt"), []).unwrap();
        std::fs::write(root.join("file-uploads/staged"), b"data").unwrap();
        for path in [
            "audio-models-v1/model",
            "avatars/preview",
            "originals/export.mp3",
            "chat-history.json",
        ] {
            std::fs::write(root.join(path), b"keep").unwrap();
        }
        #[cfg(unix)]
        std::os::unix::fs::symlink(&outside, root.join("channel-files/linked-directory")).unwrap();
        let measured = cache_files(&root, false);
        assert!(measured.error.is_none());
        assert_eq!(measured.bytes, Some(9));
        assert!(measured.items >= 3); // Includes the zero-byte download.
        let cleared = cache_files(&root, true);
        assert!(cleared.error.is_none());
        assert_eq!(cleared.removed, 9);
        assert_eq!(cleared.bytes, Some(0));
        assert_eq!(cleared.items, 0);
        for path in [
            "audio-models-v1/model",
            "avatars/preview",
            "originals/export.mp3",
            "chat-history.json",
        ] {
            assert_eq!(std::fs::read(root.join(path)).unwrap(), b"keep");
        }
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&outside, root.join("channel-files")).unwrap();
            assert!(cache_files(&root, true).error.is_none());
            assert_eq!(std::fs::read(outside.join("export.mp3")).unwrap(), b"keep");
        }
        std::fs::remove_dir_all(root).unwrap();
    }
    #[cfg(unix)]
    #[test]
    fn partial_cache_cleanup_reports_failure_and_preserves_remaining_files() {
        use std::os::unix::fs::PermissionsExt;
        extern "C" {
            fn geteuid() -> u32;
        }
        // Root bypasses the filesystem permission failure this check exercises.
        if unsafe { geteuid() } == 0 {
            return;
        }
        let root = std::env::temp_dir().join(format!("cache-permission-{}", crate::received_id()));
        let downloads = root.join("channel-files");
        std::fs::create_dir_all(&downloads).unwrap();
        std::fs::create_dir_all(root.join("file-uploads")).unwrap();
        std::fs::write(downloads.join("keep.txt"), b"keep").unwrap();
        std::fs::write(root.join("file-uploads/stage"), b"stage").unwrap();
        std::fs::set_permissions(&downloads, std::fs::Permissions::from_mode(0o500)).unwrap();
        let result = cache_files(&root, true);
        std::fs::set_permissions(&downloads, std::fs::Permissions::from_mode(0o700)).unwrap();
        assert!(result.error.is_some());
        assert_eq!(result.bytes, Some(4));
        assert_eq!(result.items, 1);
        assert_eq!(result.removed, 5);
        assert_eq!(std::fs::read(downloads.join("keep.txt")).unwrap(), b"keep");
        assert!(!root.join("file-uploads").exists());
        assert!(cache_files(&root, true).error.is_none());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn paths_sort_and_storage_are_safe() {
        for name in ["..", ".", "a/b", "a\\b", "\0", "", "x\ny"] {
            assert!(!valid_name(name));
        }
        assert!(valid_name("中文 长名称.pdf"));
        assert_eq!(icon("pdf"), "file");
        assert_eq!(icon(".pdf"), "file");
        assert_eq!(icon("文档.PDF"), "file-text");
        assert!(valid_path("/中文/文档"));
        for path in ["/../x", "//a", "/a/", "relative"] {
            assert!(!valid_path(path));
        }
        assert_eq!(parent("/中文/文档"), "/中文");
        assert_eq!(parent("/中文"), "/");
        let mut entries = vec![
            Entry {
                name: "z.PDF".into(),
                size: 0,
                timestamp: 9,
                directory: false,
                icon: icon("z.PDF"),
                local_path: None,
            },
            Entry {
                name: "中".into(),
                size: 0,
                timestamp: 0,
                directory: true,
                icon: "folder",
                local_path: None,
            },
            Entry {
                name: "a.txt".into(),
                size: 1,
                timestamp: 1,
                directory: false,
                icon: "file-text",
                local_path: None,
            },
        ];
        sort(&mut entries, false);
        assert_eq!(
            entries.iter().map(|e| e.name.as_str()).collect::<Vec<_>>(),
            vec!["中", "a.txt", "z.PDF"]
        );
        sort(&mut entries, true);
        assert_eq!(entries[1].name, "z.PDF");
        let ctx = Context {
            server: "server/../../".into(),
            channel: 42,
            channel_name: "test".into(),
        };
        let a = destination(Path::new("/safe"), &ctx, "/a", &entries[1]);
        let b = destination(Path::new("/safe"), &ctx, "/b", &entries[1]);
        assert!(a.starts_with("/safe/channel-files"));
        assert_ne!(a, b);
    }
}

#[cfg(test)]
mod flow_tests {
    use super::*;
    #[tokio::test]
    async fn cache_clear_checks_all_channels_and_invalidates_download_and_retry_state() {
        let root = std::env::temp_dir().join(format!("cache-state-{}", crate::received_id()));
        std::fs::create_dir_all(root.join("channel-files")).unwrap();
        std::fs::create_dir_all(root.join("file-uploads")).unwrap();
        let local = root.join("channel-files/zero.txt");
        let staged = root.join("file-uploads/source");
        std::fs::write(&local, []).unwrap();
        std::fs::write(&staged, b"upload").unwrap();
        let (mut files, out) = setup();
        let (tx, mut rx) = mpsc::unbounded_channel();
        files.ctx = Some(ctx(2));
        files.transfers.push(task(1)); // Running transfer in another channel.
        files.cache_command(1, true, Some(&root), &tx, &out);
        assert_eq!(
            out.lock().unwrap().events.back().unwrap()["error"],
            "files_cache_busy"
        );
        assert!(staged.exists());
        files.transfers[0].status = "complete";
        files.transfers[0].local_path = Some(local.to_string_lossy().into());
        let mut upload = task(2);
        upload.upload = true;
        upload.status = "failed";
        upload.source = Some(staged);
        files.transfers.push(upload);
        let mut downloaded = entry();
        downloaded.local_path = Some(local.to_string_lossy().into());
        files.entries = vec![downloaded];
        files.cache_command(2, true, Some(&root), &tx, &out);
        assert_eq!(files.cache.status, "clearing");
        files.cache_command(3, false, Some(&root), &tx, &out);
        assert_eq!(files.cache.status, "clearing"); // Rejection cannot unlock a running clear.
        let update = tokio::time::timeout(Duration::from_secs(3), rx.recv())
            .await
            .unwrap()
            .unwrap();
        files.update(update, &out);
        assert_eq!(files.cache.bytes, Some(0));
        assert!(files.transfers.is_empty());
        assert!(files.entries[0].local_path.is_none());
        assert_eq!(
            out.lock().unwrap().events.back().unwrap()["removedBytes"],
            6
        );
        std::fs::remove_dir_all(root).unwrap();
    }
    use tsproto_packets::packets::{Direction, Flags, OutPacket, PacketType};
    fn msg(wire: &str) -> InMessage {
        let packet = OutPacket::new_with_dir(Direction::S2C, Flags::empty(), PacketType::Command);
        InMessage::new(&packet.header(), wire.as_bytes()).unwrap()
    }
    fn ctx(channel: u64) -> Context {
        Context {
            server: "server".into(),
            channel,
            channel_name: format!("频道 {channel}"),
        }
    }
    fn entry() -> Entry {
        Entry {
            name: "中文.pdf".into(),
            size: 0,
            timestamp: 1700000000,
            directory: false,
            icon: "file-text",
            local_path: None,
        }
    }
    fn listing(c: Context, generation: u64) -> Listing {
        Listing {
            handle: MessageHandle(9),
            ctx: c,
            path: "/".into(),
            generation,
            entries: HashMap::new(),
            invalid: false,
            finished: false,
            acknowledged: false,
            deadline: Instant::now() + TIMEOUT,
            expired: false,
        }
    }
    fn setup() -> (Files, Arc<Mutex<Output>>) {
        let out = Arc::new(Mutex::new(Output::default()));
        let mut files = Files::default();
        files.apply_context(Some(ctx(1)), &out);
        files.open = true;
        files.load();
        files.queued = false;
        files.listing = Some(listing(ctx(1), files.generation));
        (files, out)
    }
    #[test]
    fn list_collects_zero_bytes_finishes_and_never_treats_permission_failure_as_empty() {
        let (mut files, out) = setup();
        files.message(&msg("notifyfilelist cid=1 path=\\/ name=中文.pdf size=0 datetime=1700000000 type=1|cid=1 path=\\/ name=文档 size=0 datetime=1690000000 type=0"),None,&out);
        files.message(&msg("notifyfilelistfinished cid=1 path=\\/"), None, &out);
        assert_eq!(files.status, "loading");
        assert!(files.entries.is_empty());
        files.list_result(MessageHandle(9), Ok(()), &out);
        assert_eq!(files.status, "ready");
        assert_eq!(files.entries[0].name, "文档");
        assert_eq!(files.entries[1].size, 0);
        files.load();
        files.listing = Some(listing(ctx(1), files.generation));
        files.list_result(
            MessageHandle(9),
            Err(tsclientlib::CommandError {
                error: tsclientlib::TsError::FileInvalidPermissions,
                missing_permission: None,
            }),
            &out,
        );
        assert_eq!(files.status, "failed");
        assert_eq!(files.error.as_deref(), Some("files_permission_denied"));
        for error in [
            tsclientlib::TsError::FileNoFilesAvailable,
            tsclientlib::TsError::DatabaseEmptyResult,
        ] {
            files.load();
            files.listing = Some(listing(ctx(1), files.generation));
            files.list_result(
                MessageHandle(9),
                Err(tsclientlib::CommandError {
                    error,
                    missing_permission: None,
                }),
                &out,
            );
            assert_eq!(files.status, "ready");
            assert!(files.entries.is_empty());
            assert!(files.error.is_none());
        }
    }
    #[test]
    fn empty_result_does_not_hide_partial_entries_or_permission_errors() {
        let empty = || tsclientlib::CommandError {
            error: tsclientlib::TsError::DatabaseEmptyResult,
            missing_permission: None,
        };
        let (mut files, out) = setup();
        files.list_result(MessageHandle(10), Err(empty()), &out);
        assert_eq!(files.status, "loading"); // An unrelated handle cannot finish this request.
        files.message(
            &msg("notifyfilelist cid=1 path=\\/ name=exists.txt size=1 datetime=1700000000 type=1"),
            None,
            &out,
        );
        files.list_result(MessageHandle(9), Err(empty()), &out);
        assert_eq!(files.status, "failed");
        assert_eq!(files.error.as_deref(), Some("DatabaseEmptyResult (0x0501)"));
        let (mut files, out) = setup();
        files.list_result(
            MessageHandle(9),
            Err(tsclientlib::CommandError {
                error: tsclientlib::TsError::DatabaseEmptyResult,
                missing_permission: Some(tsclientlib::Permission(1)),
            }),
            &out,
        );
        assert_eq!(files.status, "failed");
        assert_eq!(files.error.as_deref(), Some("files_permission_denied"));
        let (mut files, out) = setup();
        files.list_result(
            MessageHandle(9),
            Err(tsclientlib::CommandError {
                error: tsclientlib::TsError::Database,
                missing_permission: None,
            }),
            &out,
        );
        assert_eq!(files.status, "failed"); // Other database errors remain failures.
    }
    #[test]
    fn channel_switch_and_reopen_isolate_old_list_results() {
        let (mut files, out) = setup();
        let old_generation = files.generation;
        files.apply_context(Some(ctx(2)), &out);
        assert!(!files.open);
        assert_eq!(files.path, "/");
        assert!(files.entries.is_empty());
        files.open = true;
        files.load();
        files.message(
            &msg("notifyfilelist cid=1 path=\\/ name=old.txt size=10 datetime=1700000000 type=1"),
            None,
            &out,
        );
        files.message(&msg("notifyfilelistfinished cid=1 path=\\/"), None, &out);
        files.list_result(MessageHandle(9), Ok(()), &out);
        assert!(files.entries.is_empty());
        assert_eq!(files.status, "loading");
        assert!(files.listing.is_none());
        files.apply_context(Some(ctx(1)), &out);
        files.open = true;
        files.load();
        files.listing = Some(listing(ctx(1), old_generation));
        files.message(&msg("notifyfilelistfinished cid=1 path=\\/"), None, &out);
        files.list_result(MessageHandle(9), Ok(()), &out);
        assert_eq!(files.status, "loading");
        assert!(files.check("server", 2, "/").is_err());
        assert!(files.check("other-server", 1, "/").is_err());
    }
    #[test]
    fn invalid_names_fail_instead_of_making_an_empty_directory() {
        let (mut files, out) = setup();
        files.message(
            &msg("notifyfilelist cid=1 path=\\/ name=.. size=0 datetime=1700000000 type=1"),
            None,
            &out,
        );
        files.message(&msg("notifyfilelistfinished cid=1 path=\\/"), None, &out);
        files.list_result(MessageHandle(9), Ok(()), &out);
        assert_eq!(files.status, "failed");
        assert_eq!(files.error.as_deref(), Some("files_invalid_path"));
    }
    fn task(id: u64) -> Transfer {
        Transfer {
            id,
            server: "server".into(),
            channel: 1,
            path: "/".into(),
            name: "中文.pdf".into(),
            upload: false,
            size: 0,
            transferred: 0,
            status: "running",
            error: None,
            local_path: None,
            source: None,
            entry: Some(entry()),
            deadline: None,
        }
    }
    #[test]
    fn offline_upload_is_rejected_and_only_unreferenced_staging_is_removed() {
        let root = std::env::temp_dir().join(format!("offline-files-{}", crate::received_id()));
        std::fs::create_dir_all(root.join("file-uploads")).unwrap();
        let source = root.join("file-uploads/selected");
        let outside = root.join("outside");
        std::fs::write(&source, []).unwrap();
        std::fs::write(&outside, []).unwrap();
        let out = Arc::new(Mutex::new(Output::default()));
        let mut files = Files::default();
        let command = |source: &Path| Command::FilesUpload {
            server: "server".into(),
            channel: 1,
            path: "/".into(),
            name: "中文.txt".into(),
            source: source.to_string_lossy().into(),
        };
        files.offline(&command(&source), Some(&root), &out);
        assert!(!source.exists());
        assert_eq!(
            out.lock().unwrap().channel_files["error"],
            "files_disconnected"
        );
        files.offline(&command(&outside), Some(&root), &out);
        assert!(outside.exists());
        std::fs::write(&source, []).unwrap();
        let mut pending = task(1);
        pending.source = Some(std::fs::canonicalize(&source).unwrap());
        files.transfers.push(pending);
        files.offline(&command(&source), Some(&root), &out);
        assert!(source.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn transfer_handles_are_separate_and_old_tasks_do_not_mark_new_files_downloaded() {
        let (mut files, out) = setup();
        files.listing = None;
        files.status = "ready";
        files.entries = vec![entry()];
        files.transfers.push(task(1));
        files.handles.insert(FiletransferHandle(77), 1);
        assert!(files.owns(FiletransferHandle(77)));
        assert!(!files.owns(FiletransferHandle(78)));
        files.failed(
            FiletransferHandle(77),
            tsclientlib::CommandError {
                error: tsclientlib::TsError::FileTransferComplete,
                missing_permission: None,
            }
            .into(),
            &out,
        );
        assert_eq!(files.transfers[0].status, "running");
        files.entries[0].timestamp += 1;
        files.publish(&out);
        assert_eq!(out.lock().unwrap().channel_files["transfers"], json!([]));
        files.apply_context(Some(ctx(2)), &out);
        files.update(Update::Progress(1, 12), &out);
        assert_eq!(files.transfers[0].channel, 1);
        assert_eq!(out.lock().unwrap().channel_files["transfers"], json!([]));
        files.disconnected(&out);
        assert_eq!(files.transfers[0].status, "failed");
        assert!(!files.owns(FiletransferHandle(77)));
        files.update(
            Update::Finished(1, Ok(Some("/does-not-exist".into()))),
            &out,
        );
        assert_eq!(files.transfers[0].status, "failed");
    }
    #[tokio::test]
    async fn streaming_download_is_atomic_handles_zero_bytes_and_rejects_truncation() {
        let root = std::env::temp_dir().join(format!("channel-files-{}", crate::received_id()));
        for size in [0u64, 200_000] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let stream = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
                .await
                .unwrap();
            let (mut server, _) = listener.accept().await.unwrap();
            let writer = tokio::spawn(async move {
                let buffer = [7u8; 4096];
                let mut sent = 0;
                while sent < size {
                    let n = (size - sent).min(buffer.len() as u64) as usize;
                    server.write_all(&buffer[..n]).await.unwrap();
                    sent += n as u64;
                }
            });
            let mut e = entry();
            e.size = size;
            let local = destination(&root, &ctx(1), "/", &e);
            let partial = local.parent().unwrap().parent().unwrap().join(".partial-1");
            let (tx, _rx) = mpsc::unbounded_channel();
            receive(
                tsclientlib::FileDownloadResult { size, stream },
                &local,
                &partial,
                1,
                &tx,
            )
            .await
            .unwrap();
            writer.await.unwrap();
            assert!(!local.exists());
            assert_eq!(std::fs::metadata(&partial).unwrap().len(), size);
            let (mut files, out) = setup();
            let mut t = task(1);
            t.size = size;
            t.local_path = Some(local.to_string_lossy().into());
            files.transfers.push(t);
            files.update(
                Update::Finished(1, Ok(Some(partial.to_string_lossy().into()))),
                &out,
            );
            assert_eq!(files.transfers[0].status, "complete");
            assert_eq!(std::fs::metadata(local).unwrap().len(), size);
        }
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let stream = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (server, _) = listener.accept().await.unwrap();
        drop(server);
        let local = root.join("truncated/file/name");
        let partial = root.join("truncated/.partial");
        let (tx, _rx) = mpsc::unbounded_channel();
        assert!(receive(
            tsclientlib::FileDownloadResult { size: 1, stream },
            &local,
            &partial,
            1,
            &tx
        )
        .await
        .is_err());
        assert!(!local.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn upload_streams_and_waits_for_server_close_including_zero_bytes() {
        let root = std::env::temp_dir().join(format!("channel-upload-{}", crate::received_id()));
        std::fs::write(&root, [7u8; 200_000]).unwrap();
        for size in [0u64, 200_000] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let stream = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
                .await
                .unwrap();
            let (mut server, _) = listener.accept().await.unwrap();
            let reader = tokio::spawn(async move {
                let mut buffer = [0u8; 4096];
                let mut total = 0;
                while total < size {
                    let n = server
                        .read(&mut buffer[..(size - total).min(4096) as usize])
                        .await
                        .unwrap();
                    assert!(n > 0);
                    assert!(buffer[..n].iter().all(|b| *b == 7));
                    total += n as u64;
                }
                total
            });
            let (tx, _rx) = mpsc::unbounded_channel();
            send(
                tsclientlib::FileUploadResult {
                    seek_position: 0,
                    stream,
                },
                &root,
                size,
                1,
                &tx,
            )
            .await
            .unwrap();
            assert_eq!(reader.await.unwrap(), size);
        }
        std::fs::remove_file(root).unwrap();
    }
}
