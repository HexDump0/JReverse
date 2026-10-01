use std::path::PathBuf;

use tauri::State;

use crate::engine::{ClassEntry, Decompiled, Engine, EngineError, Opened};

#[tauri::command]
pub async fn open_file(engine: State<'_, Engine>, path: PathBuf) -> Result<Opened, EngineError> {
    engine.open(&path).await
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
