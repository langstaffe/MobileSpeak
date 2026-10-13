//! Session-owned downloads. Only the owner may publish a staged cache file.
use crate::{image_data_valid, MAX_IMAGE_BYTES, MAX_MEDIA_TRANSFERS};
use std::{
    collections::{HashMap, HashSet},
    path::PathBuf,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::{Duration, Instant},
};
use tokio::{
    io::AsyncReadExt,
    sync::{mpsc, OwnedSemaphorePermit, Semaphore},
    task::JoinHandle,
};
use tsclientlib::{FileDownloadResult, FiletransferHandle};

const TIMEOUT: Duration = Duration::from_secs(15);
static SEQUENCE: AtomicU64 = AtomicU64::new(0);

#[derive(Debug)]
pub struct Staged(PathBuf);
impl Drop for Staged {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
struct Job {
    key: String,
    local: PathBuf,
    deadline: Instant,
    task: Option<JoinHandle<()>>,
    slot: Option<OwnedSemaphorePermit>,
}
impl Drop for Job {
    fn drop(&mut self) {
        if let Some(task) = &self.task {
            task.abort();
        }
    }
}
pub struct Completion {
    generation: u64,
    id: u64,
    result: Result<Staged, String>,
}
pub struct Downloads {
    slots: Arc<Semaphore>,
    generation: u64,
    jobs: HashMap<u64, Job>,
    waiting: HashMap<FiletransferHandle, u64>,
    failed: HashSet<String>,
}
impl Default for Downloads {
    fn default() -> Self {
        Self {
            slots: Arc::new(Semaphore::new(MAX_MEDIA_TRANSFERS)),
            generation: 0,
            jobs: HashMap::new(),
            waiting: HashMap::new(),
            failed: HashSet::new(),
        }
    }
}
impl Downloads {
    pub fn wants(&self, key: &str, local: &std::path::Path) -> bool {
        self.slots.available_permits() > 0
            && self.jobs.len() < MAX_MEDIA_TRANSFERS
            && !self.failed.contains(key)
            && !self.jobs.values().any(|job| job.key == key)
            && !local.is_file()
    }
    fn reserve(&mut self, key: String, local: PathBuf) -> u64 {
        let slot = self
            .slots
            .clone()
            .try_acquire_owned()
            .expect("reserve only after wants");
        let id = SEQUENCE.fetch_add(1, Ordering::Relaxed);
        self.jobs.insert(
            id,
            Job {
                key,
                local,
                deadline: Instant::now() + TIMEOUT,
                task: None,
                slot: Some(slot),
            },
        );
        id
    }
    pub fn wait(&mut self, handle: FiletransferHandle, key: String, local: PathBuf) {
        let id = self.reserve(key, local);
        self.waiting.insert(handle, id);
    }
    pub fn reject(&mut self, key: String) {
        self.failed.insert(key);
    }
    pub fn failed(&mut self, handle: FiletransferHandle) {
        if let Some(id) = self.waiting.remove(&handle) {
            if let Some(job) = self.jobs.remove(&id) {
                self.failed.insert(job.key.clone());
            }
        }
    }
    pub fn download(
        &mut self,
        handle: FiletransferHandle,
        download: FileDownloadResult,
        tx: &mpsc::UnboundedSender<Completion>,
    ) {
        let Some(id) = self.waiting.remove(&handle) else {
            return;
        };
        self.spawn(id, tx, async move {
            read_image(download.stream, download.size, TIMEOUT).await
        });
    }
    pub fn badge(
        &mut self,
        key: String,
        local: PathBuf,
        url: String,
        tx: &mpsc::UnboundedSender<Completion>,
    ) {
        let id = self.reserve(key, local);
        self.spawn(id, tx, async move { read_badge(&url).await });
    }
    fn spawn(
        &mut self,
        id: u64,
        tx: &mpsc::UnboundedSender<Completion>,
        read: impl std::future::Future<Output = Result<Vec<u8>, String>> + Send + 'static,
    ) {
        let job = self.jobs.get_mut(&id).unwrap();
        let local = job.local.clone();
        let slot = job.slot.take().unwrap();
        let generation = self.generation;
        let tx = tx.clone();
        job.task = Some(tokio::spawn(async move {
            let result = async {
                let bytes = read.await?;
                // The blocking task owns cleanup even if its async waiter is aborted.
                tokio::task::spawn_blocking(move || {
                    let _slot = slot;
                    stage(local, bytes, id)
                })
                .await
                .map_err(|e| e.to_string())?
            }
            .await;
            let _ = tx.send(Completion {
                generation,
                id,
                result,
            });
        }));
    }
    pub fn complete(&mut self, completion: Completion) -> bool {
        if completion.generation != self.generation {
            return false;
        }
        let Some(job) = self.jobs.remove(&completion.id) else {
            return false;
        };
        // Cancellation and commit are serialized by the session owner. No background rename.
        let result = completion
            .result
            .and_then(|staged| std::fs::rename(&staged.0, &job.local).map_err(|e| e.to_string()));
        if let Err(error) = result {
            diagnostic!("Media download failed: {error}");
            self.failed.insert(job.key.clone());
            return false;
        }
        true
    }
    pub fn expire(&mut self, now: Instant) -> (Vec<FiletransferHandle>, bool) {
        let expired: Vec<_> = self
            .jobs
            .iter()
            .filter(|(_, j)| j.deadline <= now)
            .map(|(id, _)| *id)
            .collect();
        let changed = !expired.is_empty();
        let mut cancelled = Vec::new();
        for id in expired {
            if let Some(job) = self.jobs.remove(&id) {
                self.failed.insert(job.key.clone());
            }
            self.waiting.retain(|handle, waiting| {
                if *waiting == id {
                    cancelled.push(*handle);
                    false
                } else {
                    true
                }
            });
            diagnostic!("Media download timed out");
        }
        (cancelled, changed)
    }
    pub fn cancel(&mut self) -> Vec<FiletransferHandle> {
        self.generation += 1;
        self.jobs.clear();
        let waiting = self.waiting.drain().map(|(handle, _)| handle).collect();
        self.failed.clear();
        waiting
    }
}
fn stage(local: PathBuf, bytes: Vec<u8>, id: u64) -> Result<Staged, String> {
    let path = local.with_extension(format!("{}-{id}.partial", std::process::id()));
    let staged = Staged(path);
    let result = (|| {
        std::fs::create_dir_all(staged.0.parent().unwrap())?;
        let mut file = std::fs::OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&staged.0)?;
        std::io::Write::write_all(&mut file, &bytes)
    })();
    result.map_err(|e: std::io::Error| e.to_string())?;
    Ok(staged)
}
async fn read_image(
    mut stream: tokio::net::TcpStream,
    size: u64,
    timeout: Duration,
) -> Result<Vec<u8>, String> {
    if size == 0 || size > MAX_IMAGE_BYTES {
        return Err("Invalid image size".into());
    }
    let mut bytes = vec![0; size as usize];
    tokio::time::timeout(timeout, stream.read_exact(&mut bytes))
        .await
        .map_err(|_| "Image read timed out".to_owned())?
        .map_err(|e| e.to_string())?;
    if !image_data_valid(&bytes) {
        return Err("Invalid image data".into());
    }
    Ok(bytes)
}
async fn read_badge(url: &str) -> Result<Vec<u8>, String> {
    let client = reqwest::Client::builder()
        .timeout(TIMEOUT)
        .build()
        .map_err(|e| e.to_string())?;
    let mut response = client
        .get(url)
        .send()
        .await
        .and_then(reqwest::Response::error_for_status)
        .map_err(|e| e.to_string())?;
    if response
        .content_length()
        .is_some_and(|n| n == 0 || n > MAX_IMAGE_BYTES)
    {
        return Err("Invalid badge size".into());
    }
    let mut bytes = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|e| e.to_string())? {
        if bytes.len() + chunk.len() > MAX_IMAGE_BYTES as usize {
            return Err("Badge too large".into());
        }
        bytes.extend_from_slice(&chunk);
    }
    if !image_data_valid(&bytes) {
        return Err("Invalid badge data".into());
    }
    Ok(bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::{
        io::AsyncWriteExt,
        net::{TcpListener, TcpStream},
    };
    const PNG: &[u8] = b"\x89PNG\r\n\x1a\n";
    fn root() -> PathBuf {
        std::env::temp_dir().join(format!(
            "mobilespeak-media-{}-{}",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ))
    }
    async fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let client = TcpStream::connect(listener.local_addr().unwrap())
            .await
            .unwrap();
        (client, listener.accept().await.unwrap().0)
    }
    #[tokio::test]
    async fn exact_length_needs_no_eof_and_stalls_and_truncation_fail() {
        let (client, mut server) = pair().await;
        server.write_all(PNG).await.unwrap();
        assert_eq!(
            read_image(client, PNG.len() as u64, Duration::from_millis(100))
                .await
                .unwrap(),
            PNG
        );
        let (client, _server) = pair().await;
        assert!(read_image(client, 8, Duration::from_millis(10))
            .await
            .unwrap_err()
            .contains("timed out"));
        let (client, mut server) = pair().await;
        server.write_all(&PNG[..4]).await.unwrap();
        drop(server);
        assert!(read_image(client, 8, Duration::from_secs(1)).await.is_err());
    }
    #[tokio::test]
    async fn cap_covers_waiting_and_reading_and_cancel_closes_streams() {
        let root = root();
        let mut downloads = Downloads::default();
        let (tx, _rx) = mpsc::unbounded_channel();
        let mut peers = Vec::new();
        for i in 0..MAX_MEDIA_TRANSFERS {
            let key = i.to_string();
            let local = root.join(&key);
            assert!(downloads.wants(&key, &local));
            downloads.wait(FiletransferHandle(i as u16), key, local);
            let (stream, peer) = pair().await;
            peers.push(peer);
            downloads.download(
                FiletransferHandle(i as u16),
                FileDownloadResult { size: 8, stream },
                &tx,
            );
        }
        assert!(!downloads.wants("new member", &root.join("new")));
        drop(downloads); // Same RAII path as an exceptional session exit.
        for mut peer in peers {
            assert_eq!(
                tokio::time::timeout(Duration::from_secs(1), peer.read(&mut [0; 1]))
                    .await
                    .unwrap()
                    .unwrap(),
                0
            );
        }
        assert!(!root.exists());
    }
    #[tokio::test]
    async fn stale_completion_never_publishes_or_removes_a_new_job() {
        let root = root();
        let local = root.join("avatar.img");
        let mut downloads = Downloads::default();
        let (tx, mut rx) = mpsc::unbounded_channel();
        downloads.wait(FiletransferHandle(1), "avatar".into(), local.clone());
        let (stream, mut peer) = pair().await;
        peer.write_all(PNG).await.unwrap();
        downloads.download(
            FiletransferHandle(1),
            FileDownloadResult { size: 8, stream },
            &tx,
        );
        let old = rx.recv().await.unwrap();
        assert!(old.result.is_ok());
        downloads.cancel();
        downloads.wait(FiletransferHandle(2), "avatar".into(), local.clone());
        assert!(!downloads.complete(old));
        assert_eq!(downloads.jobs.len(), 1);
        assert!(!local.exists());
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        let (stream, mut peer) = pair().await;
        peer.write_all(PNG).await.unwrap();
        downloads.download(
            FiletransferHandle(2),
            FileDownloadResult { size: 8, stream },
            &tx,
        );
        assert!(downloads.complete(rx.recv().await.unwrap()));
        assert_eq!(std::fs::read(&local).unwrap(), PNG);
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn aborted_blocking_writer_cleans_its_own_file() {
        let root = root();
        let local = root.join("cache.img");
        let (started, start) = mpsc::unbounded_channel();
        let (release, wait) = std::sync::mpsc::channel();
        let path = local.clone();
        let outer = tokio::spawn(async move {
            tokio::task::spawn_blocking(move || {
                let staged =
                    stage(path, PNG.to_vec(), SEQUENCE.fetch_add(1, Ordering::Relaxed)).unwrap();
                started.send(()).unwrap();
                wait.recv().unwrap();
                staged
            })
            .await
            .unwrap()
        });
        let mut start = start;
        start.recv().await.unwrap();
        outer.abort();
        assert!(outer.await.unwrap_err().is_cancelled());
        release.send(()).unwrap();
        // A separate blocking barrier on the single writer completion is unnecessary for
        // correctness; wait only for cleanup visibility under a bounded test deadline.
        tokio::time::timeout(Duration::from_secs(1), async {
            while std::fs::read_dir(&root).unwrap().count() != 0 {
                tokio::task::yield_now().await;
            }
        })
        .await
        .unwrap();
        assert!(!local.exists());
        std::fs::remove_dir_all(root).unwrap();
    }
    #[tokio::test]
    async fn cancellation_keeps_blocking_write_slots_until_the_writers_exit() {
        let root = root();
        let mut downloads = Downloads::default();
        let (started, mut starts) = mpsc::unbounded_channel();
        let mut writers = Vec::new();
        let mut releases = Vec::new();
        for i in 0..MAX_MEDIA_TRANSFERS {
            let local = root.join(i.to_string());
            let id = downloads.reserve(i.to_string(), local.clone());
            let slot = downloads.jobs.get_mut(&id).unwrap().slot.take().unwrap();
            let (release, wait) = std::sync::mpsc::channel();
            releases.push(release);
            let started = started.clone();
            writers.push(tokio::task::spawn_blocking(move || {
                let _slot = slot;
                let staged = stage(local, PNG.to_vec(), id).unwrap();
                started.send(()).unwrap();
                wait.recv().unwrap();
                staged
            }));
        }
        for _ in 0..MAX_MEDIA_TRANSFERS {
            starts.recv().await.unwrap();
        }
        downloads.cancel();
        assert!(!downloads.wants("new connection", &root.join("new")));
        for release in releases {
            release.send(()).unwrap();
        }
        for writer in writers {
            drop(writer.await.unwrap());
        }
        assert!(downloads.wants("new connection", &root.join("new")));
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 0);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[tokio::test]
    async fn http_badge_is_staged_and_cancelled_like_protocol_media() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}/badge", listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut request = [0; 1024];
            stream.read(&mut request).await.unwrap();
            stream
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 8\r\n\r\n")
                .await
                .unwrap();
            stream.write_all(PNG).await.unwrap();
        });
        assert_eq!(read_badge(&url).await.unwrap(), PNG);
        server.await.unwrap();
        let mut downloads = Downloads::default();
        let root = root();
        downloads.wait(FiletransferHandle(42), "waiting".into(), root);
        assert_eq!(
            downloads.expire(Instant::now() + TIMEOUT),
            (vec![FiletransferHandle(42)], true)
        );
        assert!(downloads.jobs.is_empty());
    }
}
