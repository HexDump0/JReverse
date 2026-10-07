use std::path::PathBuf;

use serde_json::{json, Map, Value};
use tauri::{AppHandle, Manager, State};

use crate::engine::{ClassEntry, Decompiled, Engine, EngineError, Opened};
use crate::peek::Peek;
use crate::projects::Projects;
use crate::recents::{Recent, RecentView, Recents};

#[tauri::command]
pub async fn open_file(
    engine: State<'_, Engine>,
    recents: State<'_, Recents>,
    path: PathBuf,
    deobfuscate: Option<bool>,
) -> Result<Opened, EngineError> {
    let opened = engine.open(&path, deobfuscate.unwrap_or(false)).await?;
    recents.record(&path, &opened.kind, opened.class_count);
    Ok(opened)
}

#[tauri::command]
pub async fn list_classes(engine: State<'_, Engine>, session: String) -> Result<Vec<ClassEntry>, EngineError> {
    engine.list_classes(&session).await
}

#[tauri::command]
pub async fn decompile_class(
    engine: State<'_, Engine>,
    session: String,
    class_id: String,
    decompiler: Option<String>,
) -> Result<Decompiled, EngineError> {
    engine.decompile(&session, &class_id, decompiler.as_deref()).await
}

fn params(value: Value) -> Map<String, Value> {
    match value {
        Value::Object(map) => map,
        _ => Map::new(),
    }
}

#[tauri::command]
pub async fn smali_class(engine: State<'_, Engine>, session: String, class_id: String) -> Result<Value, EngineError> {
    engine.call(&session, "smali", params(json!({ "class": class_id }))).await
}

#[tauri::command]
pub async fn node_info(engine: State<'_, Engine>, session: String, node: String) -> Result<Value, EngineError> {
    engine.call(&session, "node", params(json!({ "node": node }))).await
}

#[tauri::command]
pub async fn find_usages(engine: State<'_, Engine>, session: String, node: String) -> Result<Value, EngineError> {
    engine.call(&session, "usages", params(json!({ "node": node }))).await
}

#[allow(clippy::too_many_arguments)]
#[tauri::command]
pub async fn search(
    engine: State<'_, Engine>,
    session: String,
    query: String,
    regex: bool,
    case_sensitive: bool,
    scopes: Vec<String>,
    limit: Option<u32>,
    ticket: Option<String>,
) -> Result<Value, EngineError> {
    let p = json!({ "query": query, "regex": regex, "caseSensitive": case_sensitive, "scopes": scopes, "limit": limit, "ticket": ticket });
    engine.call(&session, "search", params(p)).await
}

#[tauri::command]
pub async fn export_sources(engine: State<'_, Engine>, session: String, dir: PathBuf, ticket: Option<String>) -> Result<Value, EngineError> {
    engine.call(&session, "export", params(json!({ "dir": dir, "ticket": ticket }))).await
}

#[tauri::command]
pub async fn strings(engine: State<'_, Engine>, session: String, ticket: Option<String>) -> Result<Value, EngineError> {
    engine.call(&session, "strings", params(json!({ "ticket": ticket }))).await
}

#[tauri::command]
pub async fn read_mappings(engine: State<'_, Engine>, session: String, path: PathBuf) -> Result<Value, EngineError> {
    engine.call(&session, "readMappings", params(json!({ "path": path }))).await
}

#[tauri::command]
pub async fn write_mappings(
    engine: State<'_, Engine>,
    session: String,
    path: PathBuf,
    format: String,
    renames: Value,
    comments: Value,
) -> Result<Value, EngineError> {
    let p = json!({ "path": path, "format": format, "renames": renames, "comments": comments });
    engine.call(&session, "writeMappings", params(p)).await
}

#[tauri::command]
pub async fn cancel_job(engine: State<'_, Engine>, ticket: String) -> Result<(), EngineError> {
    engine.cancel(&ticket).await
}

#[tauri::command]
pub async fn overview(engine: State<'_, Engine>, session: String) -> Result<Value, EngineError> {
    engine.call(&session, "overview", Map::new()).await
}

#[tauri::command]
pub async fn set_code_data(engine: State<'_, Engine>, session: String, renames: Value, comments: Value) -> Result<Value, EngineError> {
    engine.call(&session, "setCodeData", params(json!({ "renames": renames, "comments": comments }))).await
}

#[tauri::command]
pub async fn list_files(engine: State<'_, Engine>, session: String) -> Result<Value, EngineError> {
    engine.call(&session, "files", Map::new()).await
}

#[tauri::command]
pub async fn read_file(engine: State<'_, Engine>, session: String, path: String) -> Result<Value, EngineError> {
    engine.call(&session, "file", params(json!({ "path": path }))).await
}

/// Renames, comments and bookmarks the user made in `path`, or null.
#[tauri::command]
pub fn load_project(projects: State<'_, Projects>, path: PathBuf) -> Option<Value> {
    projects.load(&path)
}

#[tauri::command]
pub fn save_project(projects: State<'_, Projects>, path: PathBuf, data: Value) -> Result<(), EngineError> {
    projects.save(&path, &data).map_err(|e| EngineError::remote("SAVE_FAILED", e.to_string()))
}

/// Saves text the user asked for (a class's source, a Frida script) where they chose.
#[tauri::command]
pub async fn write_text_file(path: PathBuf, contents: String) -> Result<(), EngineError> {
    tokio::fs::write(&path, contents)
        .await
        .map_err(|e| EngineError::remote("SAVE_FAILED", format!("{}: {e}", path.display())))
}

#[tauri::command]
pub async fn close_session(engine: State<'_, Engine>, session: String) -> Result<(), EngineError> {
    engine.close(&session).await
}

#[tauri::command]
pub async fn peek_file(path: PathBuf) -> Result<Peek, EngineError> {
    tauri::async_runtime::spawn_blocking(move || crate::peek::peek(&path))
        .await
        .map_err(|e| EngineError::remote("PEEK_FAILED", e.to_string()))?
        .map_err(|e| EngineError::remote("OPEN_FAILED", e.to_string()))
}

#[tauri::command]
pub fn recent_files(recents: State<'_, Recents>) -> Vec<RecentView> {
    recents.list()
}

#[tauri::command]
pub fn forget_recent(recents: State<'_, Recents>, path: PathBuf) -> Vec<RecentView> {
    recents.forget(&path);
    recents.list()
}

#[tauri::command]
pub fn replace_recents(recents: State<'_, Recents>, entries: Vec<Recent>) -> Vec<RecentView> {
    recents.replace(entries);
    recents.list()
}

/// The example app bundled next to the engine (`exampleJar` in engine/build.gradle.kts).
#[tauri::command]
pub fn example_file(app: AppHandle) -> Option<PathBuf> {
    let path = app.path().resource_dir().ok()?.join("engine").join("vault-example.jar");
    path.is_file().then_some(path)
}
