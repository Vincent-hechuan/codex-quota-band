//! PROTOTYPE ONLY.
//! Question: can a native Rust runtime keep Hook ingestion, task reduction,
//! encrypted phone payload work, low-frequency heartbeats, and foreground
//! checks comfortably below the 100 MB / 1% CPU product budget?

use chacha20poly1305::{
    aead::{Aead, KeyInit},
    ChaCha20Poly1305, Key, Nonce,
};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::{
        atomic::{AtomicU64, Ordering},
        Arc,
    },
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    sync::RwLock,
    time::{self, Duration},
};
use windows_sys::Win32::UI::WindowsAndMessaging::GetForegroundWindow;

#[derive(Clone, Serialize, Deserialize)]
struct TaskSummary {
    id: String,
    title: String,
    state: &'static str,
    updated_at_ms: u64,
}

type Tasks = Arc<RwLock<HashMap<String, TaskSummary>>>;

fn now_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}

async fn serve(listener: TcpListener, tasks: Tasks) {
    loop {
        let Ok((mut stream, _)) = listener.accept().await else {
            continue;
        };
        let tasks = tasks.clone();
        tokio::spawn(async move {
            let mut buffer = [0_u8; 4096];
            let _ = stream.read(&mut buffer).await;
            let snapshot = {
                let guard = tasks.read().await;
                serde_json::to_vec(&guard.values().cloned().collect::<Vec<_>>())
                    .unwrap_or_default()
            };
            let _ = stream.write_all(&snapshot).await;
        });
    }
}

async fn exercise_port(address: std::net::SocketAddr, label: &'static [u8]) {
    let mut tick = time::interval(Duration::from_millis(750));
    loop {
        tick.tick().await;
        if let Ok(mut stream) = TcpStream::connect(address).await {
            let _ = stream.write_all(label).await;
            let mut sink = Vec::with_capacity(4096);
            let _ = stream.read_to_end(&mut sink).await;
        }
    }
}

async fn reduce_synthetic_events(tasks: Tasks, counter: Arc<AtomicU64>) {
    let cipher = ChaCha20Poly1305::new(Key::from_slice(&[0x42; 32]));
    let states = ["处理中", "需要授权", "等待查看"];
    let mut tick = time::interval(Duration::from_millis(250));

    loop {
        tick.tick().await;
        let event = counter.fetch_add(1, Ordering::Relaxed);
        let task_number = event % 12;
        let task = TaskSummary {
            id: format!("thread-{task_number:02}"),
            title: format!("脱敏任务 {task_number:02}"),
            state: states[event as usize % states.len()],
            updated_at_ms: now_ms(),
        };
        let payload = serde_json::to_vec(&task).unwrap_or_default();
        let mut nonce_bytes = [0_u8; 12];
        nonce_bytes[4..].copy_from_slice(&event.to_be_bytes());
        let _encrypted = cipher
            .encrypt(Nonce::from_slice(&nonce_bytes), payload.as_ref())
            .unwrap_or_default();

        let mut guard = tasks.write().await;
        guard.insert(task.id.clone(), task);
        if guard.len() > 12 {
            if let Some(oldest) = guard
                .values()
                .min_by_key(|item| item.updated_at_ms)
                .map(|item| item.id.clone())
            {
                guard.remove(&oldest);
            }
        }
    }
}

async fn poll_foreground_window() {
    let mut tick = time::interval(Duration::from_millis(500));
    loop {
        tick.tick().await;
        let _foreground_handle = unsafe { GetForegroundWindow() };
    }
}

#[tokio::main(worker_threads = 2)]
async fn main() -> std::io::Result<()> {
    let tasks: Tasks = Arc::new(RwLock::new(HashMap::new()));
    let counter = Arc::new(AtomicU64::new(1));
    let hook_listener = TcpListener::bind(("127.0.0.1", 0)).await?;
    let phone_listener = TcpListener::bind(("127.0.0.1", 0)).await?;
    let hook_address = hook_listener.local_addr()?;
    let phone_address = phone_listener.local_addr()?;

    tokio::spawn(serve(hook_listener, tasks.clone()));
    tokio::spawn(serve(phone_listener, tasks.clone()));
    tokio::spawn(exercise_port(hook_address, b"{\"event\":\"hook\"}\n"));
    tokio::spawn(exercise_port(phone_address, b"{\"event\":\"phone\"}\n"));
    tokio::spawn(reduce_synthetic_events(tasks.clone(), counter.clone()));
    tokio::spawn(poll_foreground_window());

    println!(
        "READY pid={} hook={} phone={}",
        std::process::id(),
        hook_address,
        phone_address
    );

    tokio::signal::ctrl_c().await?;
    Ok(())
}
