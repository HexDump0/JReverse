use std::path::PathBuf;

use tauri::{AppHandle, Manager, State};

use crate::engine::{ClassEntry, Decompiled, Engine, EngineError, Opened};
use crate::peek::Peek;
use crate::recents::{Recent, RecentView, Recents};

#[tauri::command]
pub async fn open_file(engine: State<'_, Engine>, recents: State<'_, Recents>, path: PathBuf) -> Result<Opened, EngineError> {
    let opened = engine.open(&path).await?;
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
