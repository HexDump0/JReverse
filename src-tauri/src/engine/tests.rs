use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader, DuplexStream};
use tokio::task::JoinHandle;

use super::*;

/// What a fake engine does on startup.
#[derive(Clone, Copy)]
enum Boot {
    Normal,
    Protocol(u64),
    ExitImmediately,
}

/// Spawns in-memory fake engines and keeps handles so tests can crash them.
struct FakeEngines {
    boot: Boot,
    running: Mutex<Vec<JoinHandle<()>>>,
    opens: AtomicUsize,
    /// Every `setCodeData` the fakes received, as `(engine session, renames)`.
    code_data: Mutex<Vec<(String, Value)>>,
}

impl FakeEngines {
    fn new(boot: Boot) -> Arc<Self> {
        Arc::new(Self { boot, running: Mutex::new(Vec::new()), opens: AtomicUsize::new(0), code_data: Mutex::new(Vec::new()) })
    }

    fn engine(self: &Arc<Self>) -> Engine {
        self.engine_with(Arc::new(|_| {}))
    }

    fn engine_with(self: &Arc<Self>, sink: EventSink) -> Engine {
        let fakes = self.clone();
        let spawn: SpawnFn = Arc::new(move || {
            let (client_in, engine_in) = tokio::io::duplex(64 * 1024);
            let (engine_out, client_out) = tokio::io::duplex(64 * 1024);
            let task = tokio::spawn(fake_engine(fakes.clone(), engine_in, engine_out));
            fakes.running.lock().unwrap().push(task);
            Ok(Pipes { stdin: Box::new(client_in), stdout: Box::new(client_out), stderr: None, child: None })
        });
        Engine::new(spawn, sink)
    }

    /// Simulates the JVM dying: its pipes close mid-request.
    fn crash_all(&self) {
        for task in self.running.lock().unwrap().drain(..) {
            task.abort();
        }
    }
}

async fn fake_engine(fakes: Arc<FakeEngines>, input: DuplexStream, output: DuplexStream) {
    let out = Arc::new(tokio::sync::Mutex::new(output));
    let send = |out: Arc<tokio::sync::Mutex<DuplexStream>>, msg: Value| async move {
        let mut out = out.lock().await;
        let _ = out.write_all(format!("{msg}\n").as_bytes()).await;
    };
    let protocol = match fakes.boot {
        Boot::ExitImmediately => return,
        Boot::Protocol(p) => p,
        Boot::Normal => PROTOCOL,
    };
    send(out.clone(), json!({"method": "ready", "params": {"protocol": protocol, "version": "fake", "engines": ["jadx"]}})).await;

    let mut lines = BufReader::new(input).lines();
    // A JoinSet aborts its tasks when dropped, so crashing the fake closes every pipe.
    let mut tasks = tokio::task::JoinSet::new();
    while let Ok(Some(line)) = lines.next_line().await {
        let req: Value = serde_json::from_str(&line).unwrap();
        let id = req["id"].clone();
        let params = req["params"].clone();
        let method = req["method"].as_str().unwrap().to_string();
        if method == "setCodeData" {
            let session = params["session"].as_str().unwrap().to_string();
            fakes.code_data.lock().unwrap().push((session, params["renames"].clone()));
            send(out.clone(), json!({"id": id, "result": {"applied": 1}})).await;
            continue;
        }
        if method == "search" {
            let ticket = params["ticket"].clone();
            send(out.clone(), json!({"method": "progress", "params": {"ticket": ticket, "done": 1, "total": 2}})).await;
            send(out.clone(), json!({"id": id, "result": {"hits": [], "truncated": false, "searched": 2, "ms": 1}})).await;
            continue;
        }
        if method == "open" {
            let n = fakes.opens.fetch_add(1, Ordering::SeqCst) + 1;
            send(out.clone(), json!({"id": id, "result": {"session": format!("s{n}"), "kind": "jar", "classCount": 2, "ms": 1}})).await;
            continue;
        }
        // Answer everything else on its own task so slow requests finish out of order.
        let out = out.clone();
        tasks.spawn(async move {
            let reply = match method.as_str() {
                "listClasses" => json!({"id": id, "result": [{"id": "a/Fast", "kind": "class"}, {"id": "a/Slow", "kind": "class"}]}),
                "decompile" => match params["class"].as_str().unwrap() {
                    cls @ ("a/Fast" | "a/Slow") => {
                        if cls == "a/Slow" {
                            tokio::time::sleep(Duration::from_millis(200)).await;
                        }
                        json!({"id": id, "result": {"source": format!("// {cls} via {}", params["session"]), "engine": "jadx", "ms": 1, "warnings": 0}})
                    }
                    other => json!({"id": id, "error": {"code": "NO_CLASS", "message": format!("class not found: {other}")}}),
                },
                "close" => json!({"id": id, "result": {}}),
                _ => json!({"id": id, "error": {"code": "UNKNOWN_METHOD", "message": method}}),
            };
            send(out, reply).await;
        });
    }
    tasks.join_all().await;
}

#[tokio::test]
async fn open_list_decompile() {
    let fakes = FakeEngines::new(Boot::Normal);
    let engine = fakes.engine();
    let opened = engine.open(Path::new("/x/app.jar"), false).await.unwrap();
    assert_eq!(opened.kind, "jar");
    assert_eq!(opened.class_count, 2);
    let classes = engine.list_classes(&opened.session).await.unwrap();
    assert_eq!(classes.iter().map(|c| c.id.as_str()).collect::<Vec<_>>(), ["a/Fast", "a/Slow"]);
    let out = engine.decompile(&opened.session, "a/Fast", None).await.unwrap();
    assert_eq!(out.source, "// a/Fast via \"s1\"");
}

#[tokio::test]
async fn responses_are_routed_by_id() {
    let fakes = FakeEngines::new(Boot::Normal);
    let engine = fakes.engine();
    let s = engine.open(Path::new("/x/app.jar"), false).await.unwrap().session;
    let (slow, fast) = tokio::join!(engine.decompile(&s, "a/Slow", None), engine.decompile(&s, "a/Fast", None));
    assert!(slow.unwrap().source.contains("a/Slow"));
    assert!(fast.unwrap().source.contains("a/Fast"));
}

#[tokio::test]
async fn engine_errors_keep_their_code() {
    let fakes = FakeEngines::new(Boot::Normal);
    let engine = fakes.engine();
    let s = engine.open(Path::new("/x/app.jar"), false).await.unwrap().session;
    let err = engine.decompile(&s, "no/Such", None).await.unwrap_err();
    assert_eq!(err.code(), "NO_CLASS");
    assert_eq!(
        serde_json::to_value(&err).unwrap(),
        json!({"code": "NO_CLASS", "message": "class not found: no/Such"})
    );
    assert_eq!(engine.list_classes("session-99").await.unwrap_err().code(), "NO_SESSION");
}

#[tokio::test]
async fn crash_fails_pending_requests_then_restarts_and_reopens() {
    let fakes = FakeEngines::new(Boot::Normal);
    let engine = Arc::new(fakes.engine());
    let s = engine.open(Path::new("/x/app.jar"), false).await.unwrap().session;

    let pending = {
        let (engine, s) = (engine.clone(), s.clone());
        tokio::spawn(async move { engine.decompile(&s, "a/Slow", None).await })
    };
    tokio::time::sleep(Duration::from_millis(50)).await;
    fakes.crash_all();
    assert_eq!(pending.await.unwrap().unwrap_err().code(), "ENGINE_CRASHED");

    // Same session id, new engine: the file is reopened behind the scenes.
    let out = engine.decompile(&s, "a/Fast", None).await.unwrap();
    assert_eq!(out.source, "// a/Fast via \"s2\"");
    assert_eq!(fakes.opens.load(Ordering::SeqCst), 2);
}

#[tokio::test]
async fn renames_are_sent_again_after_a_restart() {
    let fakes = FakeEngines::new(Boot::Normal);
    let engine = fakes.engine();
    let s = engine.open(Path::new("/x/app.jar"), false).await.unwrap().session;
    let mut p = Map::new();
    p.insert("renames".into(), json!({"a/Fast": "Quick"}));
    engine.call(&s, "setCodeData", p).await.unwrap();

    fakes.crash_all();
    tokio::time::sleep(Duration::from_millis(50)).await; // let the client see the pipes close
    engine.decompile(&s, "a/Fast", None).await.unwrap();
    let seen = fakes.code_data.lock().unwrap().clone();
    assert_eq!(seen, [("s1".to_string(), json!({"a/Fast": "Quick"})), ("s2".to_string(), json!({"a/Fast": "Quick"}))]);
}

#[tokio::test]
async fn progress_notifications_reach_the_sink() {
    let fakes = FakeEngines::new(Boot::Normal);
    let seen = Arc::new(Mutex::new(Vec::new()));
    let sink: EventSink = {
        let seen = seen.clone();
        Arc::new(move |event| {
            if let EngineEvent::Progress(p) = event {
                seen.lock().unwrap().push(p);
            }
        })
    };
    let engine = fakes.engine_with(sink);
    let s = engine.open(Path::new("/x/app.jar"), false).await.unwrap().session;
    let mut p = Map::new();
    p.insert("query".into(), "x".into());
    p.insert("ticket".into(), "t1".into());
    let result = engine.call(&s, "search", p).await.unwrap();
    assert_eq!(result["searched"], 2);
    assert_eq!(*seen.lock().unwrap(), [json!({"ticket": "t1", "done": 1, "total": 2})]);
    engine.cancel("t1").await.unwrap_err(); // the fake doesn't know cancel, but the request is made
}

#[tokio::test]
async fn rejects_other_protocol_versions() {
    let engine = FakeEngines::new(Boot::Protocol(PROTOCOL + 1)).engine();
    let err = engine.open(Path::new("/x/app.jar"), false).await.unwrap_err();
    assert_eq!(err.code(), "ENGINE_PROTOCOL_MISMATCH");
}

#[tokio::test]
async fn reports_engine_that_exits_on_startup() {
    let engine = FakeEngines::new(Boot::ExitImmediately).engine();
    let err = engine.open(Path::new("/x/app.jar"), false).await.unwrap_err();
    assert_eq!(err.code(), "ENGINE_HANDSHAKE_FAILED");
}

#[tokio::test]
async fn missing_runtime_is_reported_on_use() {
    let launch = Launch::resolve(Some(Path::new("/definitely/not/here")));
    let engine = Engine::from_launch(launch, Arc::new(|_| {}));
    let err = engine.open(Path::new("/x/app.jar"), false).await.unwrap_err();
    assert_eq!(err.code(), "ENGINE_NOT_FOUND");
}

/// Runs the real engine from `src-tauri/engine-dist` (built by `pnpm engine`).
#[tokio::test]
async fn real_engine_survives_being_killed() {
    let dist = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("engine-dist");
    let java = dist.join("runtime").join("bin").join(if cfg!(windows) { "java.exe" } else { "java" });
    let jar = dist.join("engine.jar");
    if !java.is_file() || !jar.is_file() {
        eprintln!("skipping: {} not built (run `pnpm engine`)", dist.display());
        return;
    }
    let launch = Launch { java, jar: jar.clone(), jvm_args: vec!["-Xss8m".into()] };
    let engine = Engine::from_launch(Ok(launch), Arc::new(|_| {}));

    // The engine jar is itself a good-sized JAR to decompile.
    let opened = engine.open(&jar, false).await.unwrap();
    assert_eq!(opened.kind, "jar");
    let classes = engine.list_classes(&opened.session).await.unwrap();
    assert_eq!(classes.len() as u64, opened.class_count);
    let target = classes.iter().find(|c| c.id == "io/github/hexdump0/jreverse/engine/Main").unwrap();
    let before = engine.decompile(&opened.session, &target.id, None).await.unwrap();
    assert!(before.source.contains("class Main"), "{}", before.source);

    engine.kill().await;
    let after = engine.decompile(&opened.session, &target.id, None).await.unwrap();
    assert_eq!(after.source, before.source);

    engine.shutdown().await;
}
