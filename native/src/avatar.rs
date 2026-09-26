use super::*;
use md5::{Digest, Md5};
use std::io::{Read, Write};
use std::sync::atomic::AtomicBool;
use tokio::io::AsyncWriteExt;
use tsclientlib::messages::{
    c2s::{OutClientInfoRequestPart, OutListFiletransfersMessage, OutStopFiletransferPart},
    s2c::InMessage,
};

// Memory guards only; the server decides the effective upload limit.
const MAX_LOCAL_BYTES: usize = 8 * 1024 * 1024;
const CANDIDATES: usize = 3;
const STAGE_TIMEOUT: Duration = Duration::from_secs(30);
pub const CURRENT_FILE: &str = "default-avatar-current";
const LEGACY_UPLOAD_FILE: &str = "default-avatar-upload.jpg";
static SESSION: AtomicU64 = AtomicU64::new(0);

// The existing atomic current-reference file is also the desired-state record.
// Missing = unset (legacy images still migrate); UUID = image; "clear" = explicit intent.
const CLEAR: &str = "clear";
#[derive(Default)]
pub struct Store {
    pub root: Mutex<Option<PathBuf>>,
    pub selection: AtomicU64,
    pub revision: AtomicU64,
    pub clear: AtomicBool,
    saved_image_selection: AtomicU64,
    publication: Mutex<()>,
}

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
}

#[derive(Debug, PartialEq, Eq)]
pub enum Desired {
    Unset,
    Image(Vec<Vec<u8>>),
    Clear,
}

// Called by each native image worker, never the protocol/event worker or UI thread.
// A reserved command slot guarantees notification after the durable commit.
pub fn persist(
    store: &Arc<Store>,
    selection: u64,
    image: Option<&str>,
    out: &Arc<Mutex<Output>>,
) -> Result<(), String> {
    // Only native avatar workers serialize disk commits. UI selection and the
    // protocol worker read atomics; they never wait on filesystem work.
    let _publication = store.publication.lock().unwrap();
    let root = store
        .root
        .lock()
        .unwrap()
        .clone()
        .ok_or("Avatar storage is not configured")?;
    if let Some(id) = image {
        if !valid_id(id) {
            return Err("Invalid avatar reference".into());
        }
        let directory = root.join("avatars").join(id);
        read_file(&directory.join("preview.jpg"))?;
        for index in 0..CANDIDATES {
            read_file(&directory.join(format!("upload-{index}.jpg")))?;
        }
    }
    let path = root.join(CURRENT_FILE);
    let previous = reference(&path)?;
    durable_reference(
        &path,
        image.unwrap_or(CLEAR).as_bytes(),
        &store.selection,
        &store.saved_image_selection,
        selection,
        image.is_some(),
    )
    .map_err(|e| e.to_string())?;
    if image.is_some() {
        store
            .saved_image_selection
            .store(selection, Ordering::Release);
    }
    store.clear.store(image.is_none(), Ordering::Release);
    let revision = store
        .revision
        .fetch_add(1, Ordering::AcqRel)
        .wrapping_add(1);
    out.lock().unwrap().event(json!({"type":"avatar_local","intent":if image.is_some() {"image"} else {"clear"}, "selection":selection, "revision":revision, "preview":image.map(|id| root.join("avatars").join(id).join("preview.jpg"))}));
    // Cleanup is never authority to delete remotely. The durable reference already wins.
    let mut cleanup = Vec::new();
    if let Some(id) = previous.filter(|id| valid_id(id) && Some(id.as_str()) != image) {
        cleanup.push(root.join("avatars").join(id));
    }
    cleanup.extend([
        root.join(LEGACY_UPLOAD_FILE),
        root.join("default-avatar-preview.jpg"),
    ]);
    for path in cleanup {
        let result = if path.is_dir() {
            fs::remove_dir_all(&path)
        } else {
            fs::remove_file(&path)
        };
        if let Err(error) = result {
            if error.kind() != std::io::ErrorKind::NotFound {
                out.lock().unwrap().event(json!({"type":"avatar_cleanup_failed","revision":revision,"detail":error.to_string()}));
            }
        }
    }
    Ok(())
}

fn durable_reference(
    path: &Path,
    bytes: &[u8],
    current_selection: &AtomicU64,
    saved_image_selection: &AtomicU64,
    selection: u64,
    image: bool,
) -> std::io::Result<()> {
    let temporary = path.with_extension("tmp");
    let mut file = fs::File::create(&temporary)?;
    file.write_all(bytes)?;
    file.sync_all()?;
    // Cancellation before this commit point leaves the previous reference intact.
    // Merely choosing/cancelling a new image does not cancel a confirmed clear.
    // Only a later image that actually committed can supersede that intention.
    let cancelled = if image {
        current_selection.load(Ordering::Acquire) != selection
    } else {
        saved_image_selection.load(Ordering::Acquire) > selection
    };
    if cancelled {
        let _ = fs::remove_file(&temporary);
        return Err(std::io::Error::new(
            std::io::ErrorKind::Interrupted,
            "Avatar selection was cancelled",
        ));
    }
    fs::rename(&temporary, path)?;
    // The rename is the commit point. Directory sync is best effort on filesystems
    // that do not support it; don't report failure after replacing the desired state.
    if let Some(parent) = path.parent() {
        if let Ok(dir) = fs::File::open(parent) {
            let _ = dir.sync_all();
        }
    }
    Ok(())
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Token {
    version: u64,
    session: u64,
}

pub enum Completion {
    Loaded(Token, Result<Desired, String>),
    Written(Token, FiletransferHandle, Result<(), String>),
    Verified(Token, FiletransferHandle, Result<(), String>),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Stage {
    Load,
    Own,
    Connect,
    Write,
    Update,
    Hash,
    Download,
    Verify,
    Synced,
    Delete,
    ClearHash,
    Cleared,
    Drain,
    StopTransfer,
}
impl Stage {
    fn name(self) -> &'static str {
        match self {
            Self::Load => "local_read",
            Self::Own => "server_self",
            Self::Connect => "upload_connection",
            Self::Write => "upload_write",
            Self::Update => "hash_command",
            Self::Hash => "server_hash",
            Self::Download => "verify_connection",
            Self::Verify => "verify_file",
            Self::Synced => "synced",
            Self::Delete => "delete_command",
            Self::ClearHash => "clear_hash",
            Self::Cleared => "cleared",
            Self::Drain => "previous_transfer_query",
            Self::StopTransfer => "previous_transfer_stop",
        }
    }
}

struct Upload {
    handle: FiletransferHandle,
    token: Token,
    bytes: Vec<u8>,
    hash: String,
    remote: String,
    written: bool,
    download: Option<FiletransferHandle>,
}

// clientinfo has no response correlation ID. Keep a cancelled query until its
// ordered response/result is drained; after a timeout don't issue another in this session.
struct InfoQuery {
    token: Token,
    uid: String,
    hash: Option<String>,
    result: Option<Result<(), String>>,
    deadline: tokio::time::Instant,
}

pub struct AvatarSync {
    token: Token,
    pending: bool,
    retry_after_reconnect: bool,
    next_candidate: usize,
    revision: u64,
    clear: bool,
    deleting: Option<Token>,
    draining: bool,
    blocked: bool,
    delete_missing: bool,
    desired_clear: bool,
    drain_query: Option<Token>,
    drain_server_id: Option<u16>,
    previous_transfer: Option<(Token, FiletransferHandle)>,
    choices: Option<Vec<Vec<u8>>>,
    upload: Option<Upload>,
    stage: Stage,
    worker: Option<tokio::task::JoinHandle<()>>,
    query: Option<InfoQuery>,
    query_timed_out: bool,
    deadline: Option<tokio::time::Instant>,
    started: tokio::time::Instant,
}
impl Default for AvatarSync {
    fn default() -> Self {
        Self {
            token: Token {
                version: 0,
                session: SESSION.fetch_add(1, Ordering::Relaxed),
            },
            pending: false,
            retry_after_reconnect: true,
            next_candidate: 0,
            revision: 0,
            clear: false,
            deleting: None,
            draining: false,
            blocked: false,
            delete_missing: false,
            desired_clear: false,
            drain_query: None,
            drain_server_id: None,
            previous_transfer: None,
            choices: None,
            upload: None,
            stage: Stage::Load,
            worker: None,
            query: None,
            query_timed_out: false,
            deadline: None,
            started: tokio::time::Instant::now(),
        }
    }
}

fn status(out: &Arc<Mutex<Output>>, value: &str, detail: Option<String>) {
    out.lock()
        .unwrap()
        .event(json!({"type":"avatar_sync","status":value,"detail":detail}));
}

fn desired_matches(choices: Option<&[Vec<u8>]>, clear: bool, server_hash: &str) -> bool {
    if clear {
        server_hash.is_empty()
    } else {
        choices.is_some_and(|v| {
            v.iter()
                .any(|bytes| upload_hash(bytes).eq_ignore_ascii_case(server_hash))
        })
    }
}

fn upload_hash(bytes: &[u8]) -> String {
    format!("{:x}", Md5::digest(bytes))
}

fn upload_file_valid(bytes: &[u8]) -> bool {
    !bytes.is_empty() && bytes.len() <= MAX_LOCAL_BYTES && bytes.starts_with(&[0xff, 0xd8, 0xff])
}

fn read_file(path: &Path) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    fs::File::open(path)
        .map_err(|error| error.to_string())?
        .take((MAX_LOCAL_BYTES + 1) as u64)
        .read_to_end(&mut bytes)
        .map_err(|error| error.to_string())?;
    if !upload_file_valid(&bytes) {
        return Err("Saved avatar JPEG is invalid or exceeds the local safety limit".into());
    }
    Ok(bytes)
}

fn reference(path: &Path) -> Result<Option<String>, String> {
    match fs::File::open(path) {
        Ok(file) => {
            let mut value = String::new();
            file.take(65)
                .read_to_string(&mut value)
                .map_err(|e| e.to_string())?;
            if value.len() > 64 {
                return Err("Saved avatar reference exceeds the safety limit".into());
            }
            Ok(Some(value))
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(e.to_string()),
    }
}

fn candidates(root: &Path) -> Result<Desired, String> {
    let current = match reference(&root.join(CURRENT_FILE))? {
        Some(value) => value,
        None => {
            let legacy = root.join(LEGACY_UPLOAD_FILE);
            return if legacy.exists() {
                read_file(&legacy).map(|bytes| Desired::Image(vec![bytes]))
            } else {
                Ok(Desired::Unset)
            };
        }
    };
    let id = current.trim();
    if id == CLEAR {
        return Ok(Desired::Clear);
    }
    if !valid_id(id) {
        return Err("Saved avatar reference is invalid".into());
    }
    let directory = root.join("avatars").join(id);
    (0..CANDIDATES)
        .map(|index| read_file(&directory.join(format!("upload-{index}.jpg"))))
        .collect::<Result<Vec<_>, _>>()
        .map(Desired::Image)
}

impl AvatarSync {
    fn log(&self, event: &str, detail: Option<&str>) {
        eprintln!("avatar session={} version={} candidate={} transfer={:?} download={:?} bytes={} stage={} event={} elapsed_ms={} detail={}",
            self.token.session, self.token.version, self.next_candidate,
            self.upload.as_ref().map(|u| u.handle.0), self.upload.as_ref().and_then(|u| u.download.map(|h| h.0)),
            self.upload.as_ref().map_or(0, |u| u.bytes.len()), self.stage.name(), event,
            self.started.elapsed().as_millis(), detail.unwrap_or("none"));
    }

    fn enter(&mut self, stage: Stage) {
        self.stage = stage;
        self.deadline = Some(tokio::time::Instant::now() + STAGE_TIMEOUT);
        self.log("start", None);
    }

    fn stop(&mut self) {
        if let Some(worker) = self.worker.take() {
            worker.abort();
        }
        self.upload = None;
        self.deadline = None;
    }

    pub fn ready(&mut self) {
        self.pending = true;
    }

    pub fn observe(&mut self, store: &Arc<Store>, out: &Arc<Mutex<Output>>) {
        let revision = store.revision.load(Ordering::Acquire);
        self.desired_clear = store.clear.load(Ordering::Acquire);
        if self.revision != revision {
            self.revision = revision;
            self.changed(out);
        }
    }

    pub fn changed(&mut self, out: &Arc<Mutex<Output>>) {
        self.log("new_desired_state", None);
        // tsclientlib does not expose cancellation/server transfer IDs. Keep an
        // already issued write/command until its bounded barrier finishes. Never
        // publish its old hash after the desired version changes.
        // Delete result already arrived: its side effect is finished. A new image
        // can proceed while a cancelled clientinfo response is safely drained.
        if self.stage == Stage::ClearHash && self.deleting.is_some() {
            self.deleting = None;
            self.draining = false;
        }
        let mutating = self.upload.is_some() && !matches!(self.stage, Stage::Synced)
            || self.deleting.is_some()
            || self.previous_transfer.is_some()
            || self.draining;
        if mutating {
            self.draining = true;
            if !matches!(
                self.stage,
                Stage::Update
                    | Stage::Delete
                    | Stage::ClearHash
                    | Stage::Drain
                    | Stage::StopTransfer
            ) {
                if let Some(worker) = self.worker.take() {
                    worker.abort();
                }
                self.enter(Stage::Drain);
            }
        } else {
            self.stop();
            self.stage = Stage::Load;
        }
        self.token.version = self.token.version.wrapping_add(1);
        self.pending = !self.blocked;
        self.retry_after_reconnect = true;
        self.choices = None;
        self.clear = false;
        self.next_candidate = 0;
        if self.blocked {
            status(
                out,
                if self.desired_clear {
                    "clear_failed"
                } else {
                    "failed"
                },
                Some("avatar_operation_unconfirmed: reconnect before retry".into()),
            );
        } else {
            status(out, "idle", None);
        }
    }

    fn drained(&mut self) {
        self.stop();
        self.deleting = None;
        self.drain_query = None;
        self.drain_server_id = None;
        self.previous_transfer = None;
        self.draining = false;
        self.pending = true;
    }

    pub fn disconnected(&mut self) -> bool {
        self.log("disconnect", None);
        self.stop();
        self.token.session = SESSION.fetch_add(1, Ordering::Relaxed);
        self.token.version = self.token.version.wrapping_add(1);
        self.query = None;
        self.query_timed_out = false;
        self.deleting = None;
        self.drain_query = None;
        self.drain_server_id = None;
        self.previous_transfer = None;
        self.draining = false;
        self.blocked = false;
        self.clear = false;
        self.choices = None;
        self.pending = self.retry_after_reconnect;
        self.next_candidate = 0;
        self.retry_after_reconnect
    }

    pub fn check(
        &mut self,
        con: &mut Connection,
        root: Option<&Path>,
        tx: &mpsc::UnboundedSender<Completion>,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
        out: &Arc<Mutex<Output>>,
    ) {
        operations.retain(|_, operation| match operation {
            PendingOperation::Avatar(token) => {
                self.upload.as_ref().is_some_and(|u| u.token == *token)
                    && self.stage == Stage::Update
            }
            PendingOperation::AvatarDrain(token) | PendingOperation::AvatarStop(token) => {
                self.drain_query == Some(*token)
            }
            PendingOperation::AvatarDelete(token) => self.deleting == Some(*token),
            PendingOperation::AvatarInfo(token) => {
                self.query.as_ref().is_some_and(|q| q.token == *token)
            }
            _ => true,
        });
        if self.draining
            && !self.blocked
            && self.stage == Stage::Drain
            && self.drain_query.is_none()
        {
            let token = self
                .upload
                .as_ref()
                .map(|u| u.token)
                .or(self.previous_transfer.map(|v| v.0))
                .unwrap();
            match OutListFiletransfersMessage::new().send_with_result(con) {
                Ok(handle) => {
                    self.drain_query = Some(token);
                    operations.insert(handle, PendingOperation::AvatarDrain(token));
                }
                Err(error) => self.failed(
                    out,
                    format!("avatar_previous_transfer_query_failed: {error}"),
                ),
            }
        }
        if self.stage == Stage::ClearHash && self.deleting.is_some() {
            let empty = con
                .get_state()
                .ok()
                .and_then(|state| state.clients.get(&state.own_client))
                .is_some_and(|me| me.avatar_hash.is_empty());
            if empty && !self.delete_missing && self.query.is_none() {
                self.clear_confirmed(out);
            } else if self.query.is_none() {
                self.request_info(con, operations, out);
            }
        }

        if self.stage == Stage::Hash && self.upload.is_some() {
            let hash_matches = con
                .get_state()
                .ok()
                .and_then(|state| state.clients.get(&state.own_client))
                .is_some_and(|me| {
                    me.avatar_hash
                        .eq_ignore_ascii_case(&self.upload.as_ref().unwrap().hash)
                });
            if hash_matches {
                self.log("server_hash_confirmed", None);
                self.verify(con, out);
            } else if self.query.is_none() {
                self.request_info(con, operations, out);
            }
        }
        if !self.pending
            || self.blocked
            || self.upload.is_some()
            || self.worker.is_some()
            || self.deleting.is_some()
            || self.draining
        {
            return;
        }
        let Some(root) = root else {
            return;
        };
        if self.choices.is_none() && !self.clear {
            self.started = tokio::time::Instant::now();
            self.enter(Stage::Load);
            let root = root.to_owned();
            let token = self.token;
            let tx = tx.clone();
            self.worker = Some(tokio::spawn(async move {
                let result = tokio::task::spawn_blocking(move || candidates(&root))
                    .await
                    .map_err(|error| error.to_string())
                    .and_then(|result| result);
                let _ = tx.send(Completion::Loaded(token, result));
            }));
            status(
                out,
                if self.desired_clear {
                    "clearing"
                } else {
                    "checking"
                },
                None,
            );
            return;
        }
        let Ok(state) = con.get_state() else {
            return;
        };
        let Some(me) = state.clients.get(&state.own_client) else {
            return;
        };
        let Some(uid) = &me.uid else {
            return;
        };
        if self.clear {
            self.pending = false;
            if desired_matches(None, true, &me.avatar_hash) {
                self.log("server_already_empty", None);
                self.stage = Stage::Cleared;
                self.deadline = None;
                status(out, "cleared", None);
            } else {
                self.delete_missing = false;
                self.enter(Stage::Delete);
                // Own-avatar sentinel, not another user's UID. Server::delete_file
                // supplies cid=0/cpw through the locked library's generated API.
                match state.server.delete_file("/avatar_").send_with_result(con) {
                    Ok(handle) => {
                        self.deleting = Some(self.token);
                        operations.insert(handle, PendingOperation::AvatarDelete(self.token));
                        status(out, "clearing", None);
                    }
                    Err(error) => {
                        self.failed(out, format!("avatar_delete_request_failed: {error}"))
                    }
                }
            }
            return;
        }
        let choices = self.choices.as_ref().unwrap();
        if desired_matches(Some(choices), false, &me.avatar_hash) {
            self.pending = false;
            self.deadline = None;
            self.log("server_already_current", None);
            status(out, "up_to_date", None);
            return;
        }
        let remote = avatar_remote(uid);
        let Some(bytes) = choices.get(self.next_candidate).cloned() else {
            self.failed(
                out,
                "avatar_sizes_rejected: server rejected every prepared candidate".into(),
            );
            return;
        };
        self.pending = false;
        let hash = upload_hash(&bytes);
        self.enter(Stage::Connect);
        match con.upload_file(
            ChannelId(0),
            "/avatar",
            None,
            bytes.len() as u64,
            true,
            false,
        ) {
            Ok(handle) => {
                self.upload = Some(Upload {
                    handle,
                    token: self.token,
                    bytes,
                    hash,
                    remote,
                    written: false,
                    download: None,
                });
                self.log("upload_requested", None);
                status(out, "uploading", None);
            }
            Err(error) => self.failed(out, format!("avatar_upload_request_failed: {error}")),
        }
    }

    pub fn file_upload(
        &mut self,
        handle: FiletransferHandle,
        mut transfer: tsclientlib::FileUploadResult,
        tx: &mpsc::UnboundedSender<Completion>,
        out: &Arc<Mutex<Output>>,
    ) {
        let Some(upload) = &self.upload else {
            return;
        };
        if upload.handle != handle
            || (upload.token != self.token && !self.draining)
            || self.stage != Stage::Connect
        {
            return;
        }
        self.log("connection_ready", None);
        if transfer.seek_position != 0 {
            self.failed(
                out,
                "avatar_invalid_offset: nonzero seek position with resume disabled".into(),
            );
            return;
        }
        let bytes = upload.bytes.clone();
        let token = upload.token;
        let tx = tx.clone();
        self.enter(Stage::Write);
        self.worker = Some(tokio::spawn(async move {
            let result = async {
                transfer
                    .stream
                    .write_all(&bytes)
                    .await
                    .map_err(|error| error.to_string())?;
                transfer
                    .stream
                    .shutdown()
                    .await
                    .map_err(|error| error.to_string())
            }
            .await;
            let _ = tx.send(Completion::Written(token, handle, result));
        }));
    }

    pub fn file_failed(
        &mut self,
        handle: FiletransferHandle,
        error: tsclientlib::Error,
        out: &Arc<Mutex<Output>>,
    ) -> bool {
        let Some(upload) = &self.upload else {
            return false;
        };
        if upload.handle != handle && upload.download != Some(handle) {
            return false;
        }
        if matches!(&error, tsclientlib::Error::CommandError(c) if c.error == tsclientlib::TsError::FileTransferComplete)
        {
            // Optional server status, never a replacement for write_all + shutdown.
            self.log("transfer_complete_notification", None);
            return true;
        }
        if self.draining {
            self.log("previous_transfer_status", Some(&transfer_error(&error)));
            return true;
        }
        let detail = transfer_error(&error);
        self.log("transfer_error", Some(&detail));
        if handle == upload.handle && self.stage == Stage::Connect && size_rejection(&error) {
            self.stop();
            self.next_candidate += 1;
            if self
                .choices
                .as_ref()
                .is_some_and(|c| self.next_candidate < c.len())
            {
                self.pending = true;
            } else {
                self.failed(out, format!("avatar_sizes_rejected: {detail}"));
            }
        } else {
            self.failed(out, format!("avatar_transfer_failed: {detail}"));
        }
        true
    }

    fn accept_written(
        &mut self,
        token: Token,
        handle: FiletransferHandle,
        result: Result<(), String>,
        out: &Arc<Mutex<Output>>,
    ) -> bool {
        if (token != self.token && !self.draining)
            || self.stage != Stage::Write
            || self
                .upload
                .as_ref()
                .is_none_or(|u| u.handle != handle || u.token != token)
        {
            return false;
        }
        self.worker = None;
        match result {
            Ok(()) => {
                self.upload.as_mut().unwrap().written = true;
                self.log("write_finished", None);
                true
            }
            Err(error) => {
                self.failed(out, format!("avatar_write_failed: {error}"));
                false
            }
        }
    }

    pub fn finish_upload(
        &mut self,
        con: &mut Connection,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
        out: &Arc<Mutex<Output>>,
    ) {
        if !self.can_submit() {
            return;
        }
        // Keep the transfer handle through confirmation to catch late transfer errors.
        self.enter(Stage::Update);
        let upload = self.upload.as_ref().unwrap();
        let result = match con.get_state() {
            Ok(state) => state
                .client_update()
                .set_avatar_hash(&upload.hash)
                .send_with_result(con),
            Err(error) => Err(error),
        };
        match result {
            Ok(handle) => {
                operations.insert(handle, PendingOperation::Avatar(self.token));
            }
            Err(error) => self.failed(out, format!("avatar_update_failed: {error}")),
        }
    }

    fn can_submit(&self) -> bool {
        self.stage == Stage::Write
            && self
                .upload
                .as_ref()
                .is_some_and(|u| u.written && u.token == self.token)
    }

    pub fn updated(&mut self, token: Token, result: Result<(), String>, out: &Arc<Mutex<Output>>) {
        if self.upload.as_ref().is_none_or(|u| u.token != token) || self.stage != Stage::Update {
            return;
        }
        self.log("update_result", result.as_ref().err().map(String::as_str));
        if self.draining {
            self.drain_query = None;
            self.enter(Stage::Drain);
            return;
        }
        match result {
            Ok(()) => self.enter(Stage::Hash),
            Err(error) => self.failed(out, format!("avatar_update_rejected: {error}")),
        }
    }

    pub fn transfer_list_result(
        &mut self,
        token: Token,
        result: Result<(), String>,
        con: &mut Connection,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
        out: &Arc<Mutex<Output>>,
    ) {
        if self.drain_query != Some(token) || self.stage != Stage::Drain {
            return;
        }
        match result {
            Err(error) => self.failed(
                out,
                format!("avatar_previous_transfer_query_rejected: {error}"),
            ),
            Ok(()) => {
                if let Some(server_filetransfer_id) = self.drain_server_id {
                    // delete=false stops only this connection's own transfer. The
                    // explicit avatar delete, if desired, comes after this result.
                    self.enter(Stage::StopTransfer);
                    match (OutStopFiletransferPart {
                        server_filetransfer_id,
                        delete: false,
                    })
                    .send_with_result(con)
                    {
                        Ok(handle) => {
                            operations.insert(handle, PendingOperation::AvatarStop(token));
                        }
                        Err(error) => self.failed(
                            out,
                            format!("avatar_previous_transfer_stop_failed: {error}"),
                        ),
                    }
                } else {
                    self.drained();
                }
            }
        }
    }

    pub fn transfer_stopped(
        &mut self,
        token: Token,
        result: Result<(), String>,
        out: &Arc<Mutex<Output>>,
    ) {
        if self.drain_query != Some(token) || self.stage != Stage::StopTransfer {
            return;
        }
        match result {
            Ok(()) => {
                self.log("previous_transfer_stopped", None);
                self.drained();
            }
            Err(error) => self.failed(
                out,
                format!("avatar_previous_transfer_stop_rejected: {error}"),
            ),
        }
    }

    pub fn deleted(
        &mut self,
        token: Token,
        result: Result<(), tsclientlib::CommandError>,
        out: &Arc<Mutex<Output>>,
    ) {
        if self.deleting != Some(token) || self.stage != Stage::Delete {
            return;
        }
        if self.draining {
            self.drained();
            return;
        }
        self.log(
            "delete_result",
            result.as_ref().err().map(|_| "server_error"),
        );
        match result {
            Ok(()) => self.enter(Stage::ClearHash),
            // Only this exact error is eligible for idempotence, and only after
            // a fresh own clientinfo response confirms the empty flag.
            Err(error) if error.error == tsclientlib::TsError::FileNotFound => {
                self.delete_missing = true;
                self.enter(Stage::ClearHash);
            }
            Err(error) => self.failed(
                out,
                format!(
                    "avatar_delete_rejected: {:?} (0x{:04x}) missing_permission={:?}",
                    error.error, error.error as u32, error.missing_permission
                ),
            ),
        }
    }

    fn clear_confirmed(&mut self, out: &Arc<Mutex<Output>>) {
        self.log("clear_confirmed", None);
        if self.draining {
            self.drained();
            return;
        }
        self.deleting = None;
        self.stage = Stage::Cleared;
        self.deadline = None;
        status(out, "cleared", None);
    }

    fn request_info(
        &mut self,
        con: &mut Connection,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
        out: &Arc<Mutex<Output>>,
    ) {
        if self.query_timed_out {
            self.failed(
                out,
                "avatar_hash_unconfirmed: previous clientinfo timed out".into(),
            );
            return;
        }
        let own = con.get_state().ok().and_then(|state| {
            state.clients.get(&state.own_client).and_then(|me| {
                me.uid
                    .as_ref()
                    .map(|uid| (state.own_client, uid.to_string()))
            })
        });
        if let Some((client_id, uid)) = own {
            let token = self.deleting.unwrap_or(self.token);
            match (OutClientInfoRequestPart { client_id }).send_with_result(con) {
                Ok(handle) => {
                    self.log("clientinfo_request", None);
                    self.query = Some(InfoQuery {
                        token,
                        uid,
                        hash: None,
                        result: None,
                        deadline: tokio::time::Instant::now() + STAGE_TIMEOUT,
                    });
                    operations.insert(handle, PendingOperation::AvatarInfo(token));
                }
                Err(error) => self.failed(out, format!("avatar_hash_query_failed: {error}")),
            }
        }
    }

    pub fn server_hash_changed(&mut self, hash: &str, out: &Arc<Mutex<Output>>) {
        if self.draining {
            return;
        }
        if self.stage == Stage::Cleared && !hash.is_empty() {
            self.failed(
                out,
                "avatar_clear_hash_changed: server avatar is no longer empty".into(),
            );
            return;
        }
        if matches!(self.stage, Stage::Download | Stage::Verify | Stage::Synced)
            && self
                .upload
                .as_ref()
                .is_some_and(|u| !u.hash.eq_ignore_ascii_case(hash))
        {
            self.failed(
                out,
                "avatar_hash_mismatch: server changed avatar hash after confirmation".into(),
            );
        }
    }

    pub fn info_result(
        &mut self,
        token: Token,
        result: Result<(), String>,
        con: &mut Connection,
        out: &Arc<Mutex<Output>>,
    ) {
        if let Some(query) = &mut self.query {
            if query.token == token {
                query.result = Some(result);
            }
        }
        self.finish_query(con, out);
    }

    pub fn info(&mut self, msg: &InMessage, con: &mut Connection, out: &Arc<Mutex<Output>>) {
        if let InMessage::Filetransfer(parts) = msg {
            if self.stage == Stage::Drain && self.drain_query.is_some() {
                let handle = self
                    .upload
                    .as_ref()
                    .map(|u| u.handle)
                    .or(self.previous_transfer.map(|v| v.1));
                if let (Some(handle), Ok(state)) = (handle, con.get_state()) {
                    for part in parts.iter() {
                        if part.client_id == state.own_client
                            && part.client_filetransfer_id == handle.0
                        {
                            self.drain_server_id = Some(part.server_filetransfer_id);
                        }
                    }
                }
            }
        }
        if let InMessage::ClientInfo(parts) = msg {
            if let Some(query) = &mut self.query {
                for part in parts.iter() {
                    if part.uid.to_string() == query.uid {
                        query.hash = Some(part.avatar_hash.clone());
                    }
                }
            }
            self.finish_query(con, out);
        }
    }

    fn finish_query(&mut self, con: &mut Connection, out: &Arc<Mutex<Output>>) {
        if !self.query.as_ref().is_some_and(|q| {
            q.result
                .as_ref()
                .is_some_and(|r| r.is_err() || q.hash.is_some())
        }) {
            return;
        }
        let query = self.query.take().unwrap();
        if self.stage == Stage::ClearHash && self.deleting == Some(query.token) {
            match query.result.unwrap() {
                Ok(()) if query.hash.as_deref() == Some("") => self.clear_confirmed(out),
                Ok(()) => self.failed(
                    out,
                    "avatar_clear_hash_mismatch: own server avatar is still set".into(),
                ),
                Err(error) => self.failed(out, format!("avatar_clear_query_rejected: {error}")),
            }
            return;
        }
        if query.token != self.token || self.stage != Stage::Hash || self.upload.is_none() {
            return;
        }
        match query.result.unwrap() {
            Err(error) => self.failed(out, format!("avatar_hash_query_rejected: {error}")),
            Ok(()) => {
                if query.hash.as_ref().is_some_and(|hash| {
                    hash.eq_ignore_ascii_case(&self.upload.as_ref().unwrap().hash)
                }) {
                    self.log("server_hash_confirmed_by_query", None);
                    self.verify(con, out);
                } else {
                    self.failed(
                        out,
                        "avatar_hash_mismatch: server clientinfo differs from uploaded candidate"
                            .into(),
                    );
                }
            }
        }
    }

    fn verify(&mut self, con: &mut Connection, out: &Arc<Mutex<Output>>) {
        if self.stage != Stage::Hash {
            return;
        }
        self.enter(Stage::Download);
        let remote = &self.upload.as_ref().unwrap().remote;
        match con.download_file(ChannelId(0), remote, None, None) {
            Ok(handle) => {
                self.upload.as_mut().unwrap().download = Some(handle);
                self.log("verification_requested", None);
            }
            Err(error) => self.failed(out, format!("avatar_verify_request_failed: {error}")),
        }
    }

    pub fn file_download(
        &mut self,
        handle: FiletransferHandle,
        download: &mut Option<tsclientlib::FileDownloadResult>,
        tx: &mpsc::UnboundedSender<Completion>,
        out: &Arc<Mutex<Output>>,
    ) -> bool {
        let Some(upload) = &self.upload else {
            return false;
        };
        if upload.download != Some(handle) || self.stage != Stage::Download {
            return false;
        }
        let download = download.take().unwrap();
        self.log("verification_connection_ready", None);
        if download.size == 0
            || download.size > MAX_LOCAL_BYTES as u64
            || download.size != upload.bytes.len() as u64
        {
            self.failed(
                out,
                format!("avatar_file_size_mismatch: server_bytes={}", download.size),
            );
            return true;
        }
        let token = upload.token;
        let hash = upload.hash.clone();
        let size = upload.bytes.len();
        let tx = tx.clone();
        self.enter(Stage::Verify);
        self.worker = Some(tokio::spawn(async move {
            let mut bytes = Vec::with_capacity(size);
            let result = download
                .stream
                .take(size as u64 + 1)
                .read_to_end(&mut bytes)
                .await
                .map_err(|error| format!("avatar_verify_read_failed: {error}"))
                .and_then(|_| verify_bytes(&bytes, size, &hash));
            let _ = tx.send(Completion::Verified(token, handle, result));
        }));
        true
    }

    pub fn completed(
        &mut self,
        completion: Completion,
        con: &mut Connection,
        operations: &mut HashMap<MessageHandle, PendingOperation>,
        out: &Arc<Mutex<Output>>,
    ) {
        match completion {
            Completion::Loaded(token, result) => {
                if token != self.token || self.stage != Stage::Load || self.worker.is_none() {
                    return;
                }
                self.worker = None;
                match result {
                    Ok(Desired::Image(choices)) => {
                        self.enter(Stage::Own);
                        self.clear = false;
                        self.choices = Some(choices);
                    }
                    Ok(Desired::Clear) => {
                        self.enter(Stage::Own);
                        self.clear = true;
                        self.choices = None;
                        status(out, "clearing", None);
                    }
                    Ok(Desired::Unset) => {
                        self.deadline = None;
                        self.pending = false;
                        status(out, "idle", None);
                    }
                    Err(error) => self.failed(out, format!("avatar_local_read_failed: {error}")),
                }
            }
            Completion::Written(token, handle, result) => {
                if self.accept_written(token, handle, result, out) {
                    self.finish_upload(con, operations, out);
                }
            }
            Completion::Verified(token, handle, result) => {
                self.verified(token, handle, result, out)
            }
        }
    }

    fn verified(
        &mut self,
        token: Token,
        handle: FiletransferHandle,
        result: Result<(), String>,
        out: &Arc<Mutex<Output>>,
    ) {
        if (token != self.token && !self.draining)
            || self.stage != Stage::Verify
            || self
                .upload
                .as_ref()
                .is_none_or(|u| u.download != Some(handle) || u.token != token)
        {
            return;
        }
        self.worker = None;
        match result {
            Ok(()) => {
                if self.draining {
                    self.drained();
                    return;
                }
                self.stage = Stage::Synced;
                self.deadline = None;
                self.choices = None;
                self.log("sync_finished", None);
                status(out, "synced", None);
            }
            Err(error) => self.failed(out, error),
        }
    }

    pub fn tick(&mut self, out: &Arc<Mutex<Output>>) {
        if self
            .query
            .as_ref()
            .is_some_and(|q| tokio::time::Instant::now() >= q.deadline)
        {
            self.query = None;
            self.query_timed_out = true;
        }
        if self
            .deadline
            .is_some_and(|deadline| tokio::time::Instant::now() >= deadline)
        {
            self.failed(out, "avatar_timeout: stage deadline exceeded".into());
        }
    }

    fn failed(&mut self, out: &Arc<Mutex<Output>>, detail: String) {
        self.log("failed", Some(&detail));
        let clearing = self.desired_clear || self.clear || self.deleting.is_some();
        if self.draining
            || matches!(
                self.stage,
                Stage::Connect | Stage::Write | Stage::Update | Stage::Delete
            ) && detail.contains("timeout")
        {
            self.blocked = true;
        }
        self.deleting = None;
        self.drain_query = None;
        self.drain_server_id = None;
        self.previous_transfer = None;
        self.draining = false;
        let detail = format!(
            "stage={} candidate={} elapsed_ms={}: {detail}",
            self.stage.name(),
            self.next_candidate,
            self.started.elapsed().as_millis()
        );
        let phase = self.stage.name();
        // A failed transport may still exist on the server. Retain only its IDs
        // (no stream, task or bytes) so an explicit new intent can stop it first.
        let previous_transfer = self
            .upload
            .as_ref()
            .filter(|_| self.stage != Stage::Synced)
            .map(|u| (u.token, u.handle));
        self.stop();
        self.previous_transfer = previous_transfer;
        self.choices = None;
        self.pending = false;
        self.retry_after_reconnect = false;
        out.lock().unwrap().event(
            json!({"type":"avatar_sync", "status":if clearing {"clear_failed"} else {"failed"}, "phase":phase, "detail":detail}),
        );
    }
}

pub async fn offline(store: &Arc<Store>, out: &Arc<Mutex<Output>>) {
    let root = store.root.lock().unwrap().clone();
    let revision = store.revision.load(Ordering::Acquire);
    if let Some(root) = root {
        let result = tokio::task::spawn_blocking(move || candidates(&root)).await;
        let mut output = out.lock().unwrap();
        if store.revision.load(Ordering::Acquire) != revision {
            return;
        }
        match result {
            Ok(Ok(Desired::Clear)) => {
                output.event(json!({"type":"avatar_local","intent":"clear"}));
                output.event(json!({"type":"avatar_sync","status":"clear_pending","detail":null}));
            }
            Ok(Err(error)) => output.event(json!({"type":"avatar_sync","status":"failed","detail":format!("avatar_local_read_failed: {error}")})),
            Ok(Ok(Desired::Image(_))) => {
                output.event(json!({"type":"avatar_local","intent":"image"}));
                output.event(json!({"type":"avatar_sync","status":"idle","detail":null}));
            }
            _ => output.event(json!({"type":"avatar_sync","status":"idle","detail":null})),
        }
    }
}

impl Drop for AvatarSync {
    fn drop(&mut self) {
        self.stop();
    }
}

fn size_rejection(error: &tsclientlib::Error) -> bool {
    // FileInvalidSize/ExceedsSuppliedSize indicate invalid declared/sent length,
    // not proof of an avatar permission limit. Never hide those by compression.
    matches!(error, tsclientlib::Error::CommandError(c) if matches!(c.error,
        tsclientlib::TsError::PermissionInvalidSize | tsclientlib::TsError::FileExceedsFileSystemMaximumSize))
}

fn transfer_error(error: &tsclientlib::Error) -> String {
    match error {
        tsclientlib::Error::CommandError(c) => format!(
            "{:?} (0x{:04x}) missing_permission={:?}",
            c.error, c.error as u32, c.missing_permission
        ),
        _ => error.to_string(),
    }
}

fn verify_bytes(bytes: &[u8], size: usize, hash: &str) -> Result<(), String> {
    if bytes.len() != size {
        return Err(format!(
            "avatar_file_size_mismatch: expected={size} actual={}",
            bytes.len()
        ));
    }
    if !upload_hash(bytes).eq_ignore_ascii_case(hash) {
        return Err(
            "avatar_file_hash_mismatch: server bytes differ from uploaded candidate".into(),
        );
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn setup(stage: Stage) -> (AvatarSync, Arc<Mutex<Output>>) {
        let mut sync = AvatarSync::default();
        sync.stage = stage;
        let bytes = vec![0xff, 0xd8, 0xff, 0xd9];
        sync.choices = Some(vec![
            bytes.clone(),
            vec![0xff, 0xd8, 0xff],
            vec![0xff, 0xd8, 0xff],
        ]);
        sync.upload = Some(Upload {
            handle: FiletransferHandle(7),
            token: sync.token,
            hash: upload_hash(&bytes),
            bytes,
            remote: "/avatar_test".into(),
            written: false,
            download: Some(FiletransferHandle(8)),
        });
        (sync, Arc::new(Mutex::new(Output::default())))
    }
    fn command_error(error: tsclientlib::TsError) -> tsclientlib::Error {
        tsclientlib::Error::CommandError(tsclientlib::CommandError {
            error,
            missing_permission: None,
        })
    }
    fn last_status(out: &Arc<Mutex<Output>>) -> String {
        out.lock().unwrap().events.back().unwrap()["status"]
            .as_str()
            .unwrap()
            .into()
    }

    #[test]
    fn written_without_complete_notification_can_submit() {
        let (mut sync, out) = setup(Stage::Write);
        assert!(sync.accept_written(sync.token, FiletransferHandle(7), Ok(()), &out));
        assert!(sync.can_submit());
        assert!(out.lock().unwrap().events.is_empty());
        // Execute the real submission method. The disconnected test connection
        // fails get_state, proving the flow reaches hash submission without a status notification.
        let mut con = Connection::build("localhost").connect().unwrap();
        sync.finish_upload(&mut con, &mut HashMap::new(), &out);
        assert_eq!(sync.stage, Stage::Update);
        assert!(out.lock().unwrap().events.back().unwrap()["detail"]
            .as_str()
            .unwrap()
            .contains("stage=hash_command"));
    }

    #[test]
    fn early_and_late_complete_neither_submit_nor_report_success() {
        let (mut sync, out) = setup(Stage::Write);
        assert!(sync.file_failed(
            FiletransferHandle(7),
            command_error(tsclientlib::TsError::FileTransferComplete),
            &out
        ));
        assert!(!sync.can_submit());
        sync.accept_written(sync.token, FiletransferHandle(7), Ok(()), &out);
        assert!(sync.can_submit());
        sync.enter(Stage::Update);
        assert!(!sync.can_submit());
        assert!(sync.file_failed(
            FiletransferHandle(7),
            command_error(tsclientlib::TsError::FileTransferComplete),
            &out
        ));
        sync.updated(sync.token, Ok(()), &out);
        assert_eq!(sync.stage, Stage::Hash);
        assert!(sync.file_failed(
            FiletransferHandle(7),
            command_error(tsclientlib::TsError::FileTransferComplete),
            &out
        ));
        assert!(out.lock().unwrap().events.is_empty());
    }

    #[test]
    fn write_failure_cannot_submit() {
        let (mut sync, out) = setup(Stage::Write);
        assert!(!sync.accept_written(
            sync.token,
            FiletransferHandle(7),
            Err("connection reset".into()),
            &out
        ));
        assert!(!sync.can_submit());
        assert!(sync.upload.is_none());
        assert_eq!(last_status(&out), "failed");
    }

    #[test]
    fn only_explicit_size_limit_at_request_advances_candidates_and_attempts_are_bounded() {
        let (mut sync, out) = setup(Stage::Connect);
        for candidate in 0..3 {
            sync.next_candidate = candidate;
            if sync.upload.is_none() {
                let (mut next, _) = setup(Stage::Connect);
                next.next_candidate = candidate;
                sync = next;
            }
            sync.file_failed(
                FiletransferHandle(7),
                command_error(tsclientlib::TsError::PermissionInvalidSize),
                &out,
            );
            assert_eq!(sync.next_candidate, candidate + 1);
            assert_eq!(sync.pending, candidate < 2);
        }
        assert_eq!(last_status(&out), "failed");
        for error in [
            tsclientlib::TsError::FileInvalidSize,
            tsclientlib::TsError::FileExceedsSuppliedSize,
            tsclientlib::TsError::FileCouldNotOpenConnection,
            tsclientlib::TsError::FileConnectionLost,
        ] {
            let (mut sync, out) = setup(Stage::Connect);
            sync.file_failed(FiletransferHandle(7), command_error(error), &out);
            assert!(!sync.pending);
            assert_eq!(sync.next_candidate, 0);
            assert_eq!(last_status(&out), "failed");
        }
        for stage in [
            Stage::Write,
            Stage::Update,
            Stage::Hash,
            Stage::Download,
            Stage::Verify,
        ] {
            let (mut sync, out) = setup(stage);
            sync.file_failed(
                FiletransferHandle(7),
                command_error(tsclientlib::TsError::PermissionInvalidSize),
                &out,
            );
            assert_eq!(sync.next_candidate, 0);
            assert_eq!(last_status(&out), "failed");
        }
    }

    #[test]
    fn update_rejection_and_late_transfer_error_cannot_sync() {
        let (mut sync, out) = setup(Stage::Update);
        sync.updated(sync.token, Err("PermissionDenied (0x0a08)".into()), &out);
        assert_eq!(last_status(&out), "failed");
        assert!(sync.upload.is_none());
        for stage in [
            Stage::Update,
            Stage::Hash,
            Stage::Download,
            Stage::Verify,
            Stage::Synced,
        ] {
            let (mut sync, out) = setup(stage);
            assert!(sync.file_failed(
                FiletransferHandle(7),
                command_error(tsclientlib::TsError::FileConnectionLost),
                &out
            ));
            assert_eq!(last_status(&out), "failed");
        }
    }

    #[test]
    fn server_hash_change_during_or_after_verification_invalidates_success() {
        for stage in [Stage::Download, Stage::Verify, Stage::Synced] {
            let (mut sync, out) = setup(stage);
            let expected = sync.upload.as_ref().unwrap().hash.clone();
            sync.server_hash_changed(&expected, &out);
            assert!(out.lock().unwrap().events.is_empty());
            sync.server_hash_changed("other", &out);
            assert_eq!(last_status(&out), "failed");
            assert!(sync.upload.is_none());
        }
        let (mut sync, out) = setup(Stage::Update);
        sync.server_hash_changed("early", &out);
        assert_eq!(sync.stage, Stage::Update);
        assert!(out.lock().unwrap().events.is_empty());
    }

    #[test]
    fn verification_requires_server_bytes_size_and_hash() {
        let (mut sync, out) = setup(Stage::Verify);
        let upload = sync.upload.as_ref().unwrap();
        assert!(verify_bytes(&upload.bytes, upload.bytes.len(), &upload.hash).is_ok());
        assert!(verify_bytes(b"other", upload.bytes.len(), &upload.hash).is_err());
        assert!(verify_bytes(b"same", upload.bytes.len(), &upload.hash).is_err());
        sync.verified(
            sync.token,
            FiletransferHandle(8),
            Err("avatar_file_hash_mismatch".into()),
            &out,
        );
        assert_eq!(last_status(&out), "failed");
        let (mut sync, out) = setup(Stage::Verify);
        sync.verified(sync.token, FiletransferHandle(8), Ok(()), &out);
        assert_eq!(last_status(&out), "synced");
        sync.verified(sync.token, FiletransferHandle(8), Ok(()), &out);
        assert_eq!(out.lock().unwrap().events.len(), 1);
    }

    #[test]
    fn stale_selection_cancel_disconnect_and_reused_handles_cannot_finish_new_task() {
        let (mut sync, out) = setup(Stage::Write);
        let old = sync.token;
        sync.changed(&out);
        assert!(sync.upload.is_some());
        assert!(sync.draining);
        assert_eq!(sync.stage, Stage::Drain);
        assert!(sync.pending);
        assert!(!sync.accept_written(old, FiletransferHandle(7), Ok(()), &out));
        assert_ne!(sync.stage, Stage::Update);
        sync.verified(old, FiletransferHandle(8), Ok(()), &out);
        assert_eq!(last_status(&out), "idle");
        let selected = sync.token;
        sync.disconnected();
        assert_ne!(sync.token.session, selected.session);
        assert!(!sync.accept_written(selected, FiletransferHandle(7), Ok(()), &out));
        assert!(sync.query.is_none());
        assert!(sync.choices.is_none());
    }

    #[test]
    fn failed_upload_does_not_retry_on_automatic_reconnect() {
        let (mut sync, out) = setup(Stage::Connect);
        sync.file_failed(
            FiletransferHandle(7),
            command_error(tsclientlib::TsError::FileCouldNotOpenConnection),
            &out,
        );
        assert!(!sync.disconnected());
        assert!(!sync.pending);
        assert_eq!(last_status(&out), "failed");
        sync.changed(&out);
        assert!(sync.pending);
        assert!(sync.disconnected());
        let mut manual_connection = AvatarSync::default();
        manual_connection.ready();
        assert!(manual_connection.pending);
    }

    #[tokio::test]
    async fn failed_transfer_barrier_never_restarts_or_panics() {
        let (mut sync, out) = setup(Stage::Drain);
        sync.draining = true;
        sync.desired_clear = true;
        sync.failed(&out, "PermissionDenied".into());
        let mut con = Connection::build("localhost").connect().unwrap();
        let (tx, _rx) = mpsc::unbounded_channel();
        let mut operations = HashMap::new();
        sync.check(&mut con, None, &tx, &mut operations, &out);
        assert!(operations.is_empty());
        assert!(sync.drain_query.is_none() && sync.blocked && !sync.pending);
        assert_eq!(last_status(&out), "clear_failed");
        sync.changed(&out);
        sync.check(&mut con, None, &tx, &mut operations, &out);
        assert!(operations.is_empty());
        assert!(!sync.pending);
    }

    #[test]
    fn timeout_names_every_actual_stage_and_stops_worker() {
        for stage in [
            Stage::Load,
            Stage::Connect,
            Stage::Write,
            Stage::Update,
            Stage::Hash,
            Stage::Download,
            Stage::Verify,
            Stage::Delete,
            Stage::ClearHash,
            Stage::Drain,
            Stage::StopTransfer,
        ] {
            let (mut sync, out) = setup(stage);
            sync.deadline = Some(tokio::time::Instant::now());
            sync.tick(&out);
            assert_eq!(last_status(&out), "failed");
            assert!(out.lock().unwrap().events.back().unwrap()["detail"]
                .as_str()
                .unwrap()
                .contains(stage.name()));
            assert!(sync.upload.is_none());
            assert!(sync.deadline.is_none());
            assert!(!sync.pending);
        }
    }

    #[test]
    fn query_result_order_and_hash_mismatch_are_bounded() {
        for result_first in [false, true] {
            let (mut sync, out) = setup(Stage::Hash);
            sync.query = Some(InfoQuery {
                token: sync.token,
                uid: "self".into(),
                hash: None,
                result: None,
                deadline: tokio::time::Instant::now() + STAGE_TIMEOUT,
            });
            let mut con = Connection::build("localhost").connect().unwrap();
            if result_first {
                sync.info_result(sync.token, Ok(()), &mut con, &out);
                assert!(sync.query.is_some());
            }
            sync.query.as_mut().unwrap().hash = Some("different".into());
            if !result_first {
                sync.info_result(sync.token, Ok(()), &mut con, &out);
            } else {
                sync.finish_query(&mut con, &out);
            }
            assert_eq!(last_status(&out), "failed");
            assert!(sync.query.is_none());
        }
        let (mut sync, out) = setup(Stage::Hash);
        let old = sync.token;
        sync.query = Some(InfoQuery {
            token: old,
            uid: "self".into(),
            hash: Some("old".into()),
            result: None,
            deadline: tokio::time::Instant::now(),
        });
        sync.changed(&out);
        assert!(sync.query.is_some());
        sync.tick(&out);
        assert!(sync.query_timed_out);
        assert_eq!(last_status(&out), "idle");
    }

    #[tokio::test]
    async fn actual_tcp_write_matches_declared_size_and_closes_without_complete_event() {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        let (mut server, _) = listener.accept().await.unwrap();
        let (mut sync, out) = setup(Stage::Connect);
        let expected = sync.upload.as_ref().unwrap().bytes.clone();
        let (tx, mut rx) = mpsc::unbounded_channel();
        sync.file_upload(
            FiletransferHandle(7),
            tsclientlib::FileUploadResult {
                seek_position: 0,
                stream: client,
            },
            &tx,
            &out,
        );
        let mut actual = Vec::new();
        tokio::time::timeout(Duration::from_secs(2), server.read_to_end(&mut actual))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(actual, expected);
        match rx.recv().await.unwrap() {
            Completion::Written(token, handle, result) => {
                assert!(sync.accept_written(token, handle, result, &out));
            }
            _ => panic!("expected written result"),
        }
        assert!(sync.can_submit());
    }

    #[tokio::test]
    async fn cancellation_and_timeout_drop_transfer_streams() {
        for cancel in [true, false] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
                .await
                .unwrap();
            let (mut server, _) = listener.accept().await.unwrap();
            let (mut sync, out) = setup(Stage::Connect);
            let old = sync.token;
            let (tx, mut rx) = mpsc::unbounded_channel();
            sync.file_upload(
                FiletransferHandle(7),
                tsclientlib::FileUploadResult {
                    seek_position: 0,
                    stream: client,
                },
                &tx,
                &out,
            );
            if cancel {
                sync.changed(&out);
            } else {
                sync.deadline = Some(tokio::time::Instant::now());
                sync.tick(&out);
            }
            let mut bytes = Vec::new();
            tokio::time::timeout(Duration::from_secs(2), server.read_to_end(&mut bytes))
                .await
                .unwrap()
                .unwrap();
            assert!(bytes.is_empty());
            assert!(sync.worker.is_none());
            assert_eq!(sync.upload.is_some(), cancel);
            assert_eq!(sync.draining, cancel);
            assert!(rx.try_recv().is_err());
            assert!(!sync.accept_written(old, FiletransferHandle(7), Ok(()), &out));
        }
    }

    #[tokio::test]
    async fn verification_reads_fresh_server_bytes_and_rejects_a_same_size_wrong_hash() {
        for correct in [true, false] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let client = tokio::net::TcpStream::connect(listener.local_addr().unwrap())
                .await
                .unwrap();
            let (mut server, _) = listener.accept().await.unwrap();
            let (mut sync, out) = setup(Stage::Download);
            let expected = sync.upload.as_ref().unwrap().bytes.clone();
            let bytes = if correct {
                expected
            } else {
                vec![0xff, 0xd8, 0xff, 0xee]
            };
            let (tx, mut rx) = mpsc::unbounded_channel();
            let mut download = Some(tsclientlib::FileDownloadResult {
                size: bytes.len() as u64,
                stream: client,
            });
            assert!(sync.file_download(FiletransferHandle(8), &mut download, &tx, &out));
            assert!(download.is_none());
            server.write_all(&bytes).await.unwrap();
            server.shutdown().await.unwrap();
            drop(server);
            match tokio::time::timeout(Duration::from_secs(2), rx.recv())
                .await
                .unwrap()
                .unwrap()
            {
                Completion::Verified(token, handle, result) => {
                    sync.verified(token, handle, result, &out)
                }
                _ => panic!("expected verification result"),
            }
            assert_eq!(last_status(&out), if correct { "synced" } else { "failed" });
        }
    }

    fn storage() -> (PathBuf, Arc<Store>, Arc<Mutex<Output>>) {
        let root = std::env::temp_dir().join(format!(
            "mobilespeak-avatar-state-{}-{}",
            std::process::id(),
            SESSION.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&root).unwrap();
        let store = Arc::new(Store {
            root: Mutex::new(Some(root.clone())),
            ..Default::default()
        });
        (root, store, Arc::new(Mutex::new(Output::default())))
    }
    fn stage_image(root: &Path, id: &str, suffix: u8) {
        let directory = root.join("avatars").join(id);
        fs::create_dir_all(&directory).unwrap();
        for name in [
            "preview.jpg",
            "upload-0.jpg",
            "upload-1.jpg",
            "upload-2.jpg",
        ] {
            fs::write(directory.join(name), [0xff, 0xd8, 0xff, suffix]).unwrap();
        }
    }

    #[test]
    fn desired_states_legacy_migration_and_restart_do_not_infer_delete() {
        let (root, store, out) = storage();
        assert_eq!(candidates(&root).unwrap(), Desired::Unset);
        fs::write(root.join(LEGACY_UPLOAD_FILE), [0xff, 0xd8, 0xff, 1]).unwrap();
        assert!(matches!(candidates(&root).unwrap(), Desired::Image(_)));
        fs::write(root.join(LEGACY_UPLOAD_FILE), b"damaged").unwrap();
        assert!(candidates(&root).is_err());
        stage_image(&root, "a-b", 1);
        persist(&store, 0, Some("a-b"), &out).unwrap();
        assert!(matches!(candidates(&root).unwrap(), Desired::Image(_)));
        fs::remove_file(root.join("avatars/a-b/upload-0.jpg")).unwrap();
        assert!(candidates(&root).is_err());
        persist(&store, 0, None, &out).unwrap();
        assert_eq!(candidates(&root).unwrap(), Desired::Clear);
        // A new process/store reads the same clear intent, independent of sync results.
        let restarted = Arc::new(Store {
            root: Mutex::new(Some(root.clone())),
            ..Default::default()
        });
        assert_eq!(
            candidates(restarted.root.lock().unwrap().as_ref().unwrap()).unwrap(),
            Desired::Clear
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn cancelled_failed_or_corrupt_image_cannot_replace_clear_and_failed_clear_keeps_image() {
        let (root, store, out) = storage();
        persist(&store, 0, None, &out).unwrap();
        stage_image(&root, "a", 1);
        store.selection.store(2, Ordering::Release);
        assert!(persist(&store, 1, Some("a"), &out).is_err());
        assert_eq!(candidates(&root).unwrap(), Desired::Clear);
        fs::write(root.join("avatars/a/upload-1.jpg"), b"damaged").unwrap();
        assert!(persist(&store, 2, Some("a"), &out).is_err());
        assert_eq!(candidates(&root).unwrap(), Desired::Clear);
        stage_image(&root, "a", 1);
        persist(&store, 2, Some("a"), &out).unwrap();
        let revision = store.revision.load(Ordering::Acquire);
        fs::create_dir(root.join("default-avatar-current.tmp")).unwrap();
        assert!(persist(&store, 2, None, &out).is_err());
        assert_eq!(fs::read_to_string(root.join(CURRENT_FILE)).unwrap(), "a");
        assert!(root.join("avatars/a/preview.jpg").exists());
        assert_eq!(store.revision.load(Ordering::Acquire), revision);
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn abc_clear_is_global_and_only_successful_new_image_replaces_it() {
        let (root, store, out) = storage();
        stage_image(&root, "a", 2);
        persist(&store, 0, Some("a"), &out).unwrap();
        let image2 = match candidates(&root).unwrap() {
            Desired::Image(v) => upload_hash(&v[0]),
            _ => panic!(),
        };
        let mut hashes = [image2, "image1".into(), "image1".into()];
        persist(&store, 0, None, &out).unwrap();
        assert_ne!(hashes[0], hashes[1]); // Offline clear never changes any server.
        for server in [1, 2, 0] {
            assert_eq!(candidates(&root).unwrap(), Desired::Clear);
            let mut sync = AvatarSync::default();
            sync.clear = true;
            sync.deleting = Some(sync.token);
            sync.stage = Stage::Delete;
            out.lock().unwrap().events.clear();
            sync.deleted(sync.token, Ok(()), &out);
            assert!(out
                .lock()
                .unwrap()
                .events
                .back()
                .is_none_or(|e| e["status"] != "cleared")); // command ack alone is insufficient
            hashes[server].clear(); // model an authoritative empty own-server response
            sync.clear_confirmed(&out);
            assert_eq!(last_status(&out), "cleared");
            assert_eq!(candidates(&root).unwrap(), Desired::Clear);
        }
        assert!(hashes.iter().all(String::is_empty));
        stage_image(&root, "b", 3);
        persist(&store, 0, Some("b"), &out).unwrap();
        assert!(matches!(candidates(&root).unwrap(), Desired::Image(_)));
        assert!(!store.clear.load(Ordering::Acquire));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn clear_rejection_timeout_and_hash_mismatch_never_report_success() {
        let mut con = Connection::build("localhost").connect().unwrap();
        for error in [
            tsclientlib::TsError::PermissionsClientInsufficient,
            tsclientlib::TsError::FileNotFound,
        ] {
            let (mut sync, out) = setup(Stage::Delete);
            sync.clear = true;
            sync.deleting = Some(sync.token);
            sync.deleted(
                sync.token,
                Err(tsclientlib::CommandError {
                    error,
                    missing_permission: None,
                }),
                &out,
            );
            if error == tsclientlib::TsError::FileNotFound {
                assert_eq!(sync.stage, Stage::ClearHash);
                assert!(sync.delete_missing);
                sync.query = Some(InfoQuery {
                    token: sync.token,
                    uid: "self".into(),
                    hash: Some("still_set".into()),
                    result: Some(Ok(())),
                    deadline: tokio::time::Instant::now() + STAGE_TIMEOUT,
                });
                sync.finish_query(&mut con, &out);
            }
            assert_eq!(last_status(&out), "clear_failed");
            assert!(!sync.disconnected());
        }
        let (mut sync, out) = setup(Stage::Delete);
        sync.deleting = Some(sync.token);
        sync.clear = true;
        sync.deadline = Some(tokio::time::Instant::now());
        sync.tick(&out);
        assert_eq!(last_status(&out), "clear_failed");
        assert!(sync.blocked);
        assert!(out.lock().unwrap().events.back().unwrap()["detail"]
            .as_str()
            .unwrap()
            .contains("delete_command"));
    }

    #[test]
    fn superseding_upload_waits_for_standard_stop_and_never_submits_old_hash() {
        let (mut sync, out) = setup(Stage::Write);
        let old = sync.token;
        sync.changed(&out);
        assert!(!sync.can_submit());
        assert!(!sync.accept_written(old, FiletransferHandle(7), Ok(()), &out));
        assert_eq!(sync.stage, Stage::Drain);
        sync.drain_query = Some(old);
        sync.stage = Stage::StopTransfer;
        sync.changed(&out); // another desired change still waits for the same actual operation
        assert!(sync.draining);
        sync.transfer_stopped(old, Ok(()), &out);
        assert!(sync.pending && !sync.draining && sync.upload.is_none());
        sync.updated(old, Ok(()), &out);
        assert_ne!(last_status(&out), "synced");
        let (mut sync, out) = setup(Stage::Update);
        let old = sync.token;
        sync.changed(&out);
        assert_eq!(sync.stage, Stage::Update); // already sent hash command must be drained
        sync.updated(old, Ok(()), &out);
        assert_eq!(sync.stage, Stage::Drain);
    }

    #[test]
    fn new_image_waits_for_sent_delete_and_disconnect_isolates_late_results() {
        let (mut sync, out) = setup(Stage::Delete);
        sync.upload = None;
        sync.deleting = Some(sync.token);
        let old = sync.token;
        sync.changed(&out);
        assert!(sync.draining && sync.deleting.is_some());
        sync.deleted(old, Ok(()), &out);
        assert!(sync.pending && sync.deleting.is_none() && !sync.draining);
        assert_ne!(last_status(&out), "cleared");
        sync.deleting = Some(sync.token);
        sync.stage = Stage::Delete;
        let old_session = sync.token;
        sync.disconnected();
        sync.deleted(old_session, Ok(()), &out);
        sync.transfer_stopped(old_session, Ok(()), &out);
        assert!(sync.deleting.is_none());
        assert_ne!(sync.token.session, old_session.session);
    }

    #[test]
    fn empty_hash_confirmation_orders_and_command_completion_are_bounded() {
        for result_first in [false, true] {
            let mut sync = AvatarSync::default();
            let out = Arc::new(Mutex::new(Output::default()));
            let mut con = Connection::build("localhost").connect().unwrap();
            sync.clear = true;
            sync.deleting = Some(sync.token);
            sync.stage = Stage::Delete;
            sync.deleted(sync.token, Ok(()), &out);
            sync.query = Some(InfoQuery {
                token: sync.token,
                uid: "self".into(),
                hash: None,
                result: None,
                deadline: tokio::time::Instant::now() + STAGE_TIMEOUT,
            });
            if result_first {
                sync.info_result(sync.token, Ok(()), &mut con, &out);
            }
            assert!(out.lock().unwrap().events.is_empty());
            sync.query.as_mut().unwrap().hash = Some(String::new());
            if result_first {
                sync.finish_query(&mut con, &out);
            } else {
                sync.info_result(sync.token, Ok(()), &mut con, &out);
            }
            assert_eq!(last_status(&out), "cleared");
            sync.deleted(sync.token, Ok(()), &out);
            assert_eq!(out.lock().unwrap().events.len(), 1);
        }
    }

    #[test]
    fn choosing_cancelling_or_failed_image_does_not_cancel_a_pending_confirmed_clear() {
        let (root, store, out) = storage();
        store.selection.store(1, Ordering::Release); // Confirm clear.
        store.selection.store(2, Ordering::Release); // Choose a new image.
        store.selection.store(3, Ordering::Release); // Then cancel it.
        persist(&store, 1, None, &out).unwrap();
        assert_eq!(candidates(&root).unwrap(), Desired::Clear);
        stage_image(&root, "a", 1);
        persist(&store, 3, Some("a"), &out).unwrap();
        assert!(persist(&store, 1, None, &out).is_err()); // A late clear cannot erase that saved image.
        assert!(matches!(candidates(&root).unwrap(), Desired::Image(_)));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn superseding_acknowledged_clear_cannot_emit_cleared_for_new_image() {
        let mut sync = AvatarSync::default();
        let out = Arc::new(Mutex::new(Output::default()));
        sync.deleting = Some(sync.token);
        sync.stage = Stage::ClearHash;
        sync.changed(&out);
        assert_eq!(sync.stage, Stage::Load);
        assert!(sync.deleting.is_none());
        assert_eq!(last_status(&out), "idle");
    }

    #[test]
    fn only_authoritative_matching_server_state_skips_upload_or_delete() {
        assert!(!desired_matches(None, false, "existing")); // Unset never authorizes a delete.
        assert!(desired_matches(None, true, "")); // Clear + known empty skips the command.
        assert!(!desired_matches(None, true, "existing"));
        let choices = vec![vec![0xff, 0xd8, 0xff, 1]];
        assert!(desired_matches(
            Some(&choices),
            false,
            &upload_hash(&choices[0])
        ));
        assert!(!desired_matches(Some(&choices), false, "other_server_hash"));
    }

    #[test]
    fn md5_and_local_reads_remain_bounded() {
        assert_eq!(upload_hash(b"hello"), "5d41402abc4b2a76b9719d911017c592");
        assert!(upload_file_valid(&[0xff, 0xd8, 0xff, 0xd9]));
        assert!(!upload_file_valid(&vec![0xff; MAX_LOCAL_BYTES + 1]));
        assert!(!upload_file_valid(b"not jpeg"));
    }
}
