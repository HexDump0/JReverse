//! One running engine process: the handshake, request routing by id, and the
//! tasks that pump its stdin, stdout and stderr.

use std::collections::HashMap;
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncWrite, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::{mpsc, oneshot, watch};

use super::{EngineError, EngineEvent, EventSink, Launch, Status};

/// Wire protocol version this build speaks; see `Version.PROTOCOL` in the engine.
pub const PROTOCOL: u64 = 2;

const READY_TIMEOUT: Duration = Duration::from_secs(10);
const SHUTDOWN_GRACE: Duration = Duration::from_secs(1);

/// The engine's stdio, plus the child process when there is a real one.
pub struct Pipes {
    pub stdin: Box<dyn AsyncWrite + Send + Unpin>,
    pub stdout: Box<dyn AsyncRead + Send + Unpin>,
    pub stderr: Option<Box<dyn AsyncRead + Send + Unpin>>,
    pub child: Option<Child>,
}

impl Pipes {
    pub fn spawn(launch: &Launch) -> Result<Self, EngineError> {
        let mut cmd = Command::new(&launch.java);
        cmd.args(&launch.jvm_args)
            .arg("-jar")
            .arg(&launch.jar)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .kill_on_drop(true);
        #[cfg(windows)]
        cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW: no console flash
        let mut child = cmd
            .spawn()
            .map_err(|e| EngineError::Spawn(format!("{}: {e}", launch.java.display())))?;
        let stdin = child.stdin.take().expect("stdin is piped");
        let stdout = child.stdout.take().expect("stdout is piped");
        let stderr = child.stderr.take().expect("stderr is piped");
        Ok(Self {
            stdin: Box::new(stdin),
            stdout: Box::new(stdout),
            stderr: Some(Box::new(stderr)),
            child: Some(child),
        })
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ReadyInfo {
    pub protocol: u64,
    pub version: String,
    pub engines: Vec<String>,
}

type Reply = Result<Value, EngineError>;

struct Pending {
    alive: bool,
    waiting: HashMap<u64, oneshot::Sender<Reply>>,
}

pub struct Process {
    pub generation: u64,
    pub ready: ReadyInfo,
    next_id: AtomicU64,
    pending: Arc<Mutex<Pending>>,
    outgoing: mpsc::UnboundedSender<String>,
    kill: Mutex<Option<oneshot::Sender<()>>>,
    stopping: Arc<AtomicBool>,
    closed: watch::Receiver<bool>,
}

impl Process {
    /// Waits for the engine's `ready` message, then starts routing.
    pub async fn start(pipes: Pipes, generation: u64, sink: EventSink) -> Result<Arc<Self>, EngineError> {
        let Pipes { stdin, stdout, stderr, child } = pipes;

        // Pump stderr first so a JVM that dies on startup still leaves its reason in the log.
        if let Some(stderr) = stderr {
            let sink = sink.clone();
            tokio::spawn(async move {
                let mut lines = BufReader::new(stderr).lines();
                while let Ok(Some(line)) = lines.next_line().await {
                    sink(EngineEvent::Log(line));
                }
            });
        }

        let mut lines = BufReader::new(stdout).lines();
        let first = tokio::time::timeout(READY_TIMEOUT, lines.next_line())
            .await
            .map_err(|_| EngineError::Handshake(format!("no ready message within {READY_TIMEOUT:?}")))?
            .map_err(|e| EngineError::Handshake(e.to_string()))?
            .ok_or_else(|| EngineError::Handshake("engine exited during startup; see the log".into()))?;
        let ready = parse_ready(&first)?;
        if ready.protocol != PROTOCOL {
            return Err(EngineError::ProtocolMismatch { found: ready.protocol, expected: PROTOCOL });
        }

        let pending = Arc::new(Mutex::new(Pending { alive: true, waiting: HashMap::new() }));
        let stopping = Arc::new(AtomicBool::new(false));
        let (closed_tx, closed) = watch::channel(false);

        let (outgoing, mut rx) = mpsc::unbounded_channel::<String>();
        tokio::spawn(async move {
            let mut stdin = stdin;
            while let Some(line) = rx.recv().await {
                let write = async {
                    stdin.write_all(line.as_bytes()).await?;
                    stdin.write_all(b"\n").await?;
                    stdin.flush().await
                };
                if write.await.is_err() {
                    break;
                }
            }
            // Dropping stdin sends EOF, which makes the engine exit.
        });

        {
            let pending = pending.clone();
            let stopping = stopping.clone();
            tokio::spawn(async move {
                while let Ok(Some(line)) = lines.next_line().await {
                    route(&pending, &line, &sink);
                }
                let waiting = {
                    let mut p = pending.lock().unwrap();
                    p.alive = false;
                    std::mem::take(&mut p.waiting)
                };
                for (_, tx) in waiting {
                    let _ = tx.send(Err(EngineError::Crashed));
                }
                let status = if stopping.load(Ordering::SeqCst) {
                    Status::Stopped { generation }
                } else {
                    log::warn!("engine (generation {generation}) stopped unexpectedly");
                    Status::Crashed { generation }
                };
                sink(EngineEvent::Status(status));
                let _ = closed_tx.send(true);
            });
        }

        let (kill_tx, kill_rx) = oneshot::channel::<()>();
        if let Some(mut child) = child {
            tokio::spawn(async move {
                tokio::select! {
                    status = child.wait() => log::info!("engine exited: {status:?}"),
                    _ = kill_rx => {
                        let _ = child.kill().await;
                        log::info!("engine killed");
                    }
                }
            });
        }

        Ok(Arc::new(Self {
            generation,
            ready,
            next_id: AtomicU64::new(0),
            pending,
            outgoing,
            kill: Mutex::new(Some(kill_tx)),
            stopping,
            closed,
        }))
    }

    pub fn is_alive(&self) -> bool {
        self.pending.lock().unwrap().alive
    }

    pub async fn request(&self, method: &str, params: Value) -> Reply {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed) + 1;
        let (tx, rx) = oneshot::channel();
        {
            let mut p = self.pending.lock().unwrap();
            if !p.alive {
                return Err(EngineError::Crashed);
            }
            p.waiting.insert(id, tx);
        }
        let line = json!({ "id": id, "method": method, "params": params }).to_string();
        if self.outgoing.send(line).is_err() {
            self.pending.lock().unwrap().waiting.remove(&id);
            return Err(EngineError::Crashed);
        }
        rx.await.unwrap_or(Err(EngineError::Crashed))
    }

    /// Asks the engine to exit, and kills it if it hasn't within a second.
    pub async fn shutdown(&self) {
        self.stopping.store(true, Ordering::SeqCst);
        let _ = tokio::time::timeout(SHUTDOWN_GRACE, self.request("shutdown", json!({}))).await;
        if tokio::time::timeout(SHUTDOWN_GRACE, self.wait_closed()).await.is_err() {
            self.kill();
            self.wait_closed().await;
        }
    }

    pub fn kill(&self) {
        if let Some(tx) = self.kill.lock().unwrap().take() {
            let _ = tx.send(());
        }
    }

    /// Resolves once the engine's stdout has closed.
    pub async fn wait_closed(&self) {
        let mut closed = self.closed.clone();
        let _ = closed.wait_for(|c| *c).await;
    }
}

fn parse_ready(line: &str) -> Result<ReadyInfo, EngineError> {
    let msg: Value = serde_json::from_str(line)
        .map_err(|_| EngineError::Handshake(format!("unexpected output: {}", truncate(line))))?;
    if msg.get("method").and_then(Value::as_str) != Some("ready") {
        return Err(EngineError::Handshake(format!("expected ready, got {}", truncate(line))));
    }
    serde_json::from_value(msg["params"].clone()).map_err(|e| EngineError::Handshake(e.to_string()))
}

fn route(pending: &Mutex<Pending>, line: &str, sink: &EventSink) {
    let msg: Value = match serde_json::from_str(line) {
        Ok(v) => v,
        Err(_) => {
            log::warn!("engine sent non-JSON output: {}", truncate(line));
            return;
        }
    };
    let Some(id) = msg.get("id").and_then(Value::as_u64) else {
        match msg.get("method").and_then(Value::as_str) {
            Some("progress") => sink(EngineEvent::Progress(msg["params"].clone())),
            Some(method) => log::debug!("engine notification: {method}"),
            None => log::warn!("engine message without id: {}", truncate(line)),
        }
        return;
    };
    let Some(tx) = pending.lock().unwrap().waiting.remove(&id) else {
        log::warn!("engine answered unknown request {id}");
        return;
    };
    let reply = match msg.get("error") {
        Some(err) => Err(EngineError::remote(
            err.get("code").and_then(Value::as_str).unwrap_or("INTERNAL"),
            err.get("message").and_then(Value::as_str).unwrap_or("unknown engine error"),
        )),
        None => Ok(msg.get("result").cloned().unwrap_or(Value::Null)),
    };
    let _ = tx.send(reply);
}

fn truncate(s: &str) -> &str {
    match s.char_indices().nth(200) {
        Some((i, _)) => &s[..i],
        None => s,
    }
}
