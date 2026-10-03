//! Client for the JVM engine (`engine/`), which runs the decompilers.
//!
//! The engine is started lazily on first use and restarted on the next request
//! after it dies. Sessions handed to the frontend survive a restart: the file is
//! reopened transparently and the old id keeps working.

mod error;
mod locate;
mod process;

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use tokio::sync::Mutex;

pub use error::EngineError;
pub use locate::Launch;
pub use process::{Pipes, ReadyInfo, PROTOCOL};
use process::Process;

#[derive(Debug, Clone, Serialize)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum Status {
    Starting { generation: u64 },
    Ready { generation: u64, version: String, engines: Vec<String> },
    Stopped { generation: u64 },
    Crashed { generation: u64 },
    Failed { code: String, message: String },
}

pub enum EngineEvent {
    /// One line of the engine's stderr.
    Log(String),
    Status(Status),
    /// How far a search or export has got: `{ticket, done, total}`.
    Progress(Value),
}

pub type EventSink = Arc<dyn Fn(EngineEvent) + Send + Sync>;
pub type SpawnFn = Arc<dyn Fn() -> Result<Pipes, EngineError> + Send + Sync>;

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Opened {
    pub session: String,
    pub kind: String,
    pub class_count: u64,
    /// Decompilers that can read this file, jadx first.
    pub engines: Vec<String>,
    pub ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassEntry {
    pub id: String,
    pub kind: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Decompiled {
    pub source: String,
    pub engine: String,
    pub ms: u64,
    pub warnings: u64,
    /// Spans as flat `[line, col, len, node]` quadruples; see engine/README.md.
    #[serde(default)]
    pub links: Vec<u32>,
    #[serde(default)]
    pub decls: Vec<u32>,
    #[serde(default)]
    pub nodes: Vec<Value>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct OpenResult {
    session: String,
    kind: String,
    class_count: u64,
    #[serde(default)]
    engines: Vec<String>,
    ms: u64,
}

struct SessionEntry {
    path: PathBuf,
    engine_id: String,
    generation: u64,
    /// The last `setCodeData` params, sent again after the file is reopened.
    code_data: Option<Map<String, Value>>,
}

#[derive(Default)]
struct Sessions {
    next: u64,
    open: HashMap<String, SessionEntry>,
}

pub struct Engine {
    spawn: SpawnFn,
    sink: EventSink,
    current: Mutex<Option<Arc<Process>>>,
    generation: AtomicU64,
    sessions: Mutex<Sessions>,
}

impl Engine {
    pub fn new(spawn: SpawnFn, sink: EventSink) -> Self {
        Self {
            spawn,
            sink,
            current: Mutex::new(None),
            generation: AtomicU64::new(0),
            sessions: Mutex::new(Sessions::default()),
        }
    }

    /// An engine started from `launch`; a launch error is reported on first use.
    pub fn from_launch(launch: Result<Launch, EngineError>, sink: EventSink) -> Self {
        Self::new(Arc::new(move || launch.clone().and_then(|l| Pipes::spawn(&l))), sink)
    }

    pub async fn open(&self, path: &Path) -> Result<Opened, EngineError> {
        let process = self.process().await?;
        let opened: OpenResult = decode(process.request("open", json!({ "path": path })).await?)?;
        let mut sessions = self.sessions.lock().await;
        sessions.next += 1;
        let session = format!("session-{}", sessions.next);
        sessions.open.insert(
            session.clone(),
            SessionEntry { path: path.to_path_buf(), engine_id: opened.session, generation: process.generation, code_data: None },
        );
        Ok(Opened { session, kind: opened.kind, class_count: opened.class_count, engines: opened.engines, ms: opened.ms })
    }

    pub async fn list_classes(&self, session: &str) -> Result<Vec<ClassEntry>, EngineError> {
        decode(self.session_request(session, "listClasses", Map::new()).await?)
    }

    pub async fn decompile(&self, session: &str, class_id: &str, engine: Option<&str>) -> Result<Decompiled, EngineError> {
        let mut params = Map::new();
        params.insert("class".into(), class_id.into());
        if let Some(engine) = engine {
            params.insert("engine".into(), engine.into());
        }
        decode(self.session_request(session, "decompile", params).await?)
    }

    /// Any per-session method (`smali`, `usages`, `search`, ...), passed through as JSON.
    pub async fn call(&self, session: &str, method: &str, params: Map<String, Value>) -> Result<Value, EngineError> {
        let replay = (method == "setCodeData").then(|| params.clone());
        let result = self.session_request(session, method, params).await?;
        if let Some(code_data) = replay {
            if let Some(entry) = self.sessions.lock().await.open.get_mut(session) {
                entry.code_data = Some(code_data);
            }
        }
        Ok(result)
    }

    /// Stops a running search or export. A job that already finished, or an
    /// engine that isn't running, is not an error.
    pub async fn cancel(&self, ticket: &str) -> Result<(), EngineError> {
        let current = self.current.lock().await.clone();
        if let Some(process) = current.filter(|p| p.is_alive()) {
            process.request("cancel", json!({ "ticket": ticket })).await?;
        }
        Ok(())
    }

    pub async fn close(&self, session: &str) -> Result<(), EngineError> {
        let entry = self.sessions.lock().await.open.remove(session).ok_or_else(|| no_session(session))?;
        let current = self.current.lock().await.clone();
        if let Some(process) = current.filter(|p| p.generation == entry.generation && p.is_alive()) {
            process.request("close", json!({ "session": entry.engine_id })).await?;
        }
        Ok(())
    }

    /// Stops the engine if it is running. The next request starts a new one.
    pub async fn shutdown(&self) {
        let process = self.current.lock().await.take();
        if let Some(process) = process {
            process.shutdown().await;
        }
    }

    /// Kills the engine without asking; used to test crash recovery.
    pub async fn kill(&self) {
        let process = self.current.lock().await.clone();
        if let Some(process) = process {
            process.kill();
            process.wait_closed().await;
        }
    }

    /// The running engine, starting one if there is none or the last one died.
    async fn process(&self) -> Result<Arc<Process>, EngineError> {
        let mut current = self.current.lock().await;
        if let Some(process) = current.as_ref().filter(|p| p.is_alive()) {
            return Ok(process.clone());
        }
        let generation = self.generation.fetch_add(1, Ordering::SeqCst) + 1;
        self.emit(Status::Starting { generation });
        let started = match (self.spawn)() {
            Ok(pipes) => Process::start(pipes, generation, self.sink.clone()).await,
            Err(e) => Err(e),
        };
        match started {
            Ok(process) => {
                log::info!("engine {} ready (generation {generation})", process.ready.version);
                self.emit(Status::Ready {
                    generation,
                    version: process.ready.version.clone(),
                    engines: process.ready.engines.clone(),
                });
                *current = Some(process.clone());
                Ok(process)
            }
            Err(e) => {
                log::error!("engine failed to start: {e}");
                self.emit(Status::Failed { code: e.code().into(), message: e.to_string() });
                Err(e)
            }
        }
    }

    /// Sends a request about a session, reopening its file first if the engine
    /// has restarted since the session was opened.
    async fn session_request(&self, session: &str, method: &str, mut params: Map<String, Value>) -> Result<Value, EngineError> {
        let process = self.process().await?;
        let engine_id = {
            let mut sessions = self.sessions.lock().await;
            let entry = sessions.open.get_mut(session).ok_or_else(|| no_session(session))?;
            if entry.generation != process.generation {
                log::info!("reopening {} after engine restart", entry.path.display());
                let reopened: OpenResult = decode(process.request("open", json!({ "path": entry.path })).await?)?;
                entry.engine_id = reopened.session;
                entry.generation = process.generation;
                if let Some(code_data) = &entry.code_data {
                    let mut params = code_data.clone();
                    params.insert("session".into(), entry.engine_id.clone().into());
                    process.request("setCodeData", Value::Object(params)).await?;
                }
            }
            entry.engine_id.clone()
        };
        params.insert("session".into(), engine_id.into());
        process.request(method, Value::Object(params)).await
    }

    fn emit(&self, status: Status) {
        (self.sink)(EngineEvent::Status(status));
    }
}

fn decode<T: DeserializeOwned>(value: Value) -> Result<T, EngineError> {
    serde_json::from_value(value).map_err(|e| EngineError::BadResponse(e.to_string()))
}

fn no_session(session: &str) -> EngineError {
    EngineError::remote("NO_SESSION", format!("no such session: {session}"))
}

#[cfg(test)]
mod tests;
