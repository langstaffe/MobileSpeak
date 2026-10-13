//! Ordered history worker. The protocol owner sends changes, never copies history.
use crate::{
    atomic_write, chat_path, chat_snapshot, load_chat, ChatMessage, ChatStatus, ChatStore, Output,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    path::PathBuf,
    sync::{mpsc, Arc, Mutex},
    thread::JoinHandle,
    time::{Duration, Instant},
};

enum Change {
    Select {
        epoch: u64,
        connection: Option<String>,
        root: Option<PathBuf>,
        server: Option<String>,
        retired: Option<Value>,
    },
    Add(u64, Vec<ChatMessage>),
    Finish(u64, String, ChatStatus, Option<String>),
    Fail(u64, &'static str),
    Refresh(u64),
    #[cfg(test)]
    Barrier(mpsc::Sender<()>),
    Stop,
}
pub struct Worker {
    tx: mpsc::Sender<Change>,
    thread: Option<JoinHandle<()>>,
    out: Arc<Mutex<Output>>,
}
pub struct Session<'a> {
    worker: &'a Worker,
    epoch: u64,
}
impl Worker {
    pub fn new(out: Arc<Mutex<Output>>) -> Self {
        let (tx, rx) = mpsc::channel();
        let output = out.clone();
        let thread = std::thread::Builder::new()
            .name("MobileSpeakChat".into())
            .spawn(move || {
                let mut state = History::new(output);
                loop {
                    let first = match rx.recv() {
                        Ok(change) => change,
                        Err(_) => break,
                    };
                    let mut stop = state.apply(first);
                    // Bound batching so a continuous producer cannot starve saves/publication.
                    let deadline = Instant::now() + Duration::from_millis(100);
                    for _ in 0..255 {
                        if stop || Instant::now() >= deadline {
                            break;
                        }
                        match rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
                            Ok(change) => stop = state.apply(change),
                            Err(_) => break,
                        }
                    }
                    state.flush();
                    if stop {
                        break;
                    }
                }
                state.flush();
            })
            .expect("chat worker");
        Self {
            tx,
            thread: Some(thread),
            out,
        }
    }
    pub fn session(&self) -> Session<'_> {
        Session {
            worker: self,
            epoch: self.select(None, None),
        }
    }
    fn send(&self, change: Change) {
        // A stopped storage worker is fatal to this core, never silently discard an accepted edit.
        self.tx.send(change).expect("chat worker stopped");
    }
    fn select(&self, root: Option<PathBuf>, server: Option<String>) -> u64 {
        let (epoch, connection, retired) = {
            let mut out = self.out.lock().unwrap();
            out.chat_epoch += 1;
            let retired = out.chat_snapshot.replace(json!([]));
            out.notify();
            (out.chat_epoch, out.connection_id.clone(), retired)
        };
        self.send(Change::Select {
            epoch,
            connection,
            root,
            server,
            retired,
        });
        epoch
    }
    pub fn clear(&self) {
        self.select(None, None);
    }
    #[cfg(test)]
    pub fn barrier(&self) {
        let (tx, rx) = mpsc::channel();
        self.send(Change::Barrier(tx));
        rx.recv().unwrap();
    }
}
impl Drop for Worker {
    fn drop(&mut self) {
        let _ = self.tx.send(Change::Stop);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}
impl Session<'_> {
    pub fn select(&mut self, root: Option<PathBuf>, server: Option<String>) {
        self.epoch = self.worker.select(root, server);
    }
    pub fn add(&self, messages: Vec<ChatMessage>) {
        self.worker.send(Change::Add(self.epoch, messages));
    }
    pub fn finish(&self, id: &str, status: ChatStatus, error: Option<String>) {
        self.worker
            .send(Change::Finish(self.epoch, id.into(), status, error));
    }
    pub fn fail_pending(&self, reason: &'static str) {
        self.worker.send(Change::Fail(self.epoch, reason));
    }
    pub fn refresh(&self) {
        self.worker.send(Change::Refresh(self.epoch));
    }
}
impl Drop for Session<'_> {
    fn drop(&mut self) {
        self.fail_pending("message_unconfirmed_after_reconnect");
    }
}
struct Retained {
    store: ChatStore,
    writable: bool,
    connection: Option<String>,
    server: Option<String>,
}
struct History {
    out: Arc<Mutex<Output>>,
    epoch: u64,
    connection: Option<String>,
    root: Option<PathBuf>,
    server: Option<String>,
    store: ChatStore,
    ids: HashMap<String, usize>,
    writable: bool,
    dirty: bool,
    publish: bool,
    unsaved: HashMap<PathBuf, Retained>,
}
impl History {
    fn new(out: Arc<Mutex<Output>>) -> Self {
        Self {
            out,
            epoch: 0,
            connection: None,
            root: None,
            server: None,
            store: ChatStore::default(),
            ids: HashMap::new(),
            writable: true,
            dirty: false,
            publish: false,
            unsaved: HashMap::new(),
        }
    }
    fn error(&self, code: &str, detail: String) {
        // Keep originating ID even when a newer connection owns Output now.
        self.out.lock().unwrap().event(json!({"type":"error", "code":code,"detail":detail,"scope":"global","originConnectionId":self.connection,"serverId":self.server}));
    }
    fn apply(&mut self, change: Change) -> bool {
        match change {
            Change::Select {
                epoch,
                connection,
                root,
                server,
                retired,
            } => {
                drop(retired); // Never destroy a large old snapshot on the session thread.
                self.flush();
                if self.dirty {
                    if let (Some(root), Some(server)) = (&self.root, &self.server) {
                        self.unsaved.insert(
                            chat_path(root, server),
                            Retained {
                                store: std::mem::take(&mut self.store),
                                writable: self.writable,
                                connection: self.connection.clone(),
                                server: self.server.clone(),
                            },
                        );
                    }
                }
                self.epoch = epoch;
                self.connection = connection;
                self.root = root;
                self.server = server;
                self.store = ChatStore::default();
                self.writable = true;
                self.dirty = false;
                if let (Some(root), Some(server)) = (&self.root, &self.server) {
                    if let Some(retained) = self.unsaved.remove(&chat_path(root, server)) {
                        self.store = retained.store;
                        self.writable = retained.writable;
                        self.dirty = true;
                    } else {
                        match load_chat(root, server) {
                            Ok(mut store) => {
                                self.dirty =
                                    store.fail_pending("message_unconfirmed_after_restart");
                                self.store = store;
                            }
                            Err(error) => {
                                self.writable = false;
                                self.error("chat_history_read_failed", error);
                            }
                        }
                    }
                }
                self.ids = self
                    .store
                    .messages
                    .iter()
                    .enumerate()
                    .map(|(i, m)| (m.id.clone(), i))
                    .collect();
                self.publish = true;
            }
            Change::Add(epoch, messages) if epoch == self.epoch => {
                for message in messages {
                    if self.ids.contains_key(&message.id) {
                        continue;
                    }
                    self.ids
                        .insert(message.id.clone(), self.store.messages.len());
                    self.store.messages.push(message);
                    self.dirty = true;
                    self.publish = true;
                }
            }
            Change::Finish(epoch, id, status, error) if epoch == self.epoch => {
                if let Some(index) = self.ids.get(&id) {
                    let message = &mut self.store.messages[*index];
                    if message.status == ChatStatus::Pending {
                        message.status = status;
                        message.error = error;
                        self.dirty = true;
                        self.publish = true;
                    }
                }
            }
            Change::Fail(epoch, reason) if epoch == self.epoch => {
                if self.store.fail_pending(reason) {
                    self.dirty = true;
                    self.publish = true;
                }
            }
            Change::Refresh(epoch) if epoch == self.epoch => self.publish = true,
            #[cfg(test)]
            Change::Barrier(done) => {
                self.flush();
                let _ = done.send(());
            }
            Change::Stop => {
                for (path, retained) in &self.unsaved {
                    if !retained.writable {
                        continue;
                    }
                    let result = serde_json::to_vec(&retained.store)
                        .map_err(|e| e.to_string())
                        .and_then(|data| atomic_write(path, &data).map_err(|e| e.to_string()));
                    if let Err(error) = result {
                        self.out.lock().unwrap().event(json!({"type":"error","code":"chat_history_save_failed","detail":error,
                            "scope":"global","originConnectionId":retained.connection,"serverId":retained.server}));
                    }
                }
                return true;
            }
            _ => {}
        }
        false
    }
    fn flush(&mut self) {
        if self.dirty && self.writable {
            if let (Some(root), Some(server)) = (&self.root, &self.server) {
                let result = serde_json::to_vec(&self.store)
                    .map_err(|e| e.to_string())
                    .and_then(|data| {
                        atomic_write(&chat_path(root, server), &data).map_err(|e| e.to_string())
                    });
                match result {
                    Ok(()) => self.dirty = false,
                    Err(error) => self.error("chat_history_save_failed", error),
                }
            }
        }
        if self.publish {
            let value = chat_snapshot(&self.store, self.root.as_deref(), self.server.as_deref());
            let retired = {
                let mut out = self.out.lock().unwrap();
                if out.chat_epoch == self.epoch {
                    let retired = out.chat_snapshot.replace(value);
                    out.notify();
                    retired
                } else {
                    Some(value)
                }
            };
            drop(retired);
            self.publish = false;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::ChatKind;
    fn message(id: &str, status: ChatStatus) -> ChatMessage {
        ChatMessage {
            id: id.into(),
            conversation: "channel:id:1".into(),
            kind: ChatKind::Channel,
            target_id: "id:1".into(),
            target_name: "Lobby".into(),
            sender_uid: "uid".into(),
            sender_name: "name".into(),
            sender_avatar_hash: "hash".into(),
            own: true,
            text: "hello".into(),
            timestamp: 1,
            status,
            error: None,
        }
    }
    fn root() -> PathBuf {
        std::env::temp_dir().join(format!("mobilespeak-chat-{}", crate::received_id()))
    }
    #[test]
    fn ordered_load_updates_restart_and_stale_publication() {
        let root = root();
        let out = Arc::new(Mutex::new(Output::default()));
        let path = chat_path(&root, "server");
        atomic_write(
            &path,
            &serde_json::to_vec(&ChatStore {
                messages: vec![message("old", ChatStatus::Pending)],
            })
            .unwrap(),
        )
        .unwrap();
        {
            let worker = Worker::new(out.clone());
            let mut session = worker.session();
            session.select(Some(root.clone()), Some("server".into()));
            session.add(vec![message("new", ChatStatus::Pending)]);
            session.finish("new", ChatStatus::Sent, None);
            session.finish("new", ChatStatus::Failed, Some("late".into()));
            session.add(vec![message("new", ChatStatus::Pending)]);
            worker.barrier();
            let store = load_chat(&root, "server").unwrap();
            assert_eq!(store.messages.len(), 2);
            assert!(store.messages[0].status == ChatStatus::Failed);
            assert!(store.messages[1].status == ChatStatus::Sent);
            let previous = session.epoch;
            session.select(Some(root.clone()), Some("other".into()));
            worker.send(Change::Add(
                previous,
                vec![message("stale", ChatStatus::Received)],
            ));
            worker.barrier();
            assert_eq!(out.lock().unwrap().chat_snapshot, Some(json!([])));
            session.select(Some(root.clone()), Some("server".into()));
            worker.barrier();
            assert_eq!(
                out.lock()
                    .unwrap()
                    .chat_snapshot
                    .as_ref()
                    .unwrap()
                    .as_array()
                    .unwrap()
                    .len(),
                2
            );
            session.add(vec![message("exit", ChatStatus::Pending)]);
        } // Session fail-pending and Worker stop flush everything already accepted.
        let store = load_chat(&root, "server").unwrap();
        assert_eq!(store.messages.len(), 3);
        assert!(store.messages[2].status == ChatStatus::Failed);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[test]
    fn corrupt_load_is_not_overwritten_and_save_failure_is_reported() {
        let root = root();
        let path = chat_path(&root, "server");
        atomic_write(&path, b"corrupt").unwrap();
        let out = Arc::new(Mutex::new(Output::default()));
        {
            let worker = Worker::new(out.clone());
            let mut session = worker.session();
            session.select(Some(root.clone()), Some("server".into()));
            session.add(vec![message("live", ChatStatus::Received)]);
            worker.barrier();
            assert_eq!(std::fs::read(&path).unwrap(), b"corrupt");
            assert!(out
                .lock()
                .unwrap()
                .events
                .iter()
                .any(|e| e["code"] == "chat_history_read_failed"));
        }
        std::fs::remove_file(&path).unwrap();
        {
            let worker = Worker::new(out.clone());
            let mut session = worker.session();
            session.select(Some(root.clone()), Some("server".into()));
            worker.barrier();
            std::fs::create_dir(&path).unwrap(); // destination cannot be replaced by a file
            session.add(vec![message("live", ChatStatus::Received)]);
            worker.barrier();
            assert!(out
                .lock()
                .unwrap()
                .events
                .iter()
                .any(|e| e["code"] == "chat_history_save_failed"));
            session.select(Some(root.clone()), Some("other".into()));
            worker.barrier();
            session.select(Some(root.clone()), Some("server".into()));
            worker.barrier();
            assert_eq!(
                out.lock()
                    .unwrap()
                    .chat_snapshot
                    .as_ref()
                    .unwrap()
                    .as_array()
                    .unwrap()
                    .len(),
                1
            );
            std::fs::remove_dir(&path).unwrap();
        } // retries the dirty store on shutdown
        assert_eq!(load_chat(&root, "server").unwrap().messages.len(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
}

#[cfg(test)]
mod performance {
    use super::*;
    use crate::{playback::Playback, poll_output, tsclientlib_audio::AudioHandler, ChatKind};
    #[test]
    #[ignore = "synthetic load measurement; run in release with CHAT_BENCH_SIZE"]
    fn long_history_session_audio_and_output_cost() {
        let n: usize = std::env::var("CHAT_BENCH_SIZE")
            .unwrap_or("50000".into())
            .parse()
            .unwrap();
        let root = std::env::temp_dir().join(crate::received_id());
        let message = |i: usize| ChatMessage {
            id: i.to_string(),
            conversation: "channel:id:1".into(),
            kind: ChatKind::Channel,
            target_id: "id:1".into(),
            target_name: "Lobby".into(),
            sender_uid: format!("uid-{}", i % 10),
            sender_name: "Speaker".into(),
            sender_avatar_hash: "hash".into(),
            own: true,
            text: "x".repeat(200),
            timestamp: i as u64,
            status: ChatStatus::Sent,
            error: None,
        };
        atomic_write(
            &chat_path(&root, "server"),
            &serde_json::to_vec(&ChatStore {
                messages: (0..n).map(message).collect(),
            })
            .unwrap(),
        )
        .unwrap();
        let out = Arc::new(Mutex::new(Output::default()));
        out.lock().unwrap().status("connected");
        let worker = Worker::new(out.clone());
        let mut session = worker.session();
        let load = Instant::now();
        session.select(Some(root.clone()), Some("server".into()));
        worker.barrier();
        let load_ms = load.elapsed().as_millis();
        let encoded = Instant::now();
        let initial_bytes = serde_json::to_vec(&poll_output(&out)).unwrap();
        let encode_ms = encoded.elapsed().as_millis();
        let bytes = initial_bytes.len();
        drop(initial_bytes);
        let (playback, refill) = Playback::new().unwrap();
        let playback = Arc::new(playback);
        playback.set_active(true);
        let rendering = playback.clone();
        let render = std::thread::spawn(move || {
            for _ in 0..100 {
                let mut pcm = [0f32; 1920];
                unsafe {
                    rendering.render(pcm.as_mut_ptr(), pcm.as_mut_ptr().add(1), 960, 2);
                }
                std::thread::sleep(Duration::from_millis(20));
            }
        });
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let (max_work, max_gap) = runtime.block_on(async {
            let refill = tokio::net::UnixDatagram::from_std(refill).unwrap();
            let mut audio = AudioHandler::new(); let mut signal = [0];
            let mut clock = tokio::time::interval(Duration::from_millis(20));
            clock.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            let mut last = Instant::now(); let mut max_gap = 0; let mut max_work = 0; let mut i = 0;
            while i < 100 {
                tokio::select! {
                    _ = refill.recv(&mut signal) => {
                        max_gap = max_gap.max(last.elapsed().as_micros()); last = Instant::now();
                        playback.acknowledged(); playback.refill_with_proc(&mut audio, |_, _| {}, |_| {});
                        playback.prepared(playback.revision.load(std::sync::atomic::Ordering::Acquire));
                    }
                    _ = clock.tick() => {
                        let start = Instant::now(); session.add(vec![message(n+i)]);
                        max_work = max_work.max(start.elapsed().as_micros()); i += 1;
                    }
                }
            }
            (max_work, max_gap)
        });
        render.join().unwrap();
        worker.barrier();
        let start = Instant::now();
        let envelope = poll_output(&out);
        let poll_us = start.elapsed().as_micros();
        assert_eq!(envelope["chats"].as_array().unwrap().len(), n + 100);
        println!("CHAT_BENCH n={n} load_and_initial_publish_ms={load_ms} full_json_encode_ms={encode_ms} bytes={bytes} max_session_change_us={max_work} output_poll_us={poll_us} output_lock_max_ns={} max_synthetic_refill_gap_us={max_gap}", crate::POLL_LOCK_NANOS.load(std::sync::atomic::Ordering::Relaxed));
        drop(session);
        drop(worker);
        std::fs::remove_dir_all(root).unwrap();
    }
}
