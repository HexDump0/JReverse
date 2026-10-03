mod commands;
pub mod engine;
mod peek;
mod projects;
mod recents;

use std::sync::Arc;

use tauri::{Emitter, Manager, RunEvent};

use engine::{Engine, EngineEvent, EventSink, Launch};
use projects::Projects;
use recents::Recents;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_log::Builder::new().level(log::LevelFilter::Info).build())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            let launch = Launch::resolve(app.path().resource_dir().ok().as_deref());
            match &launch {
                Ok(l) => log::info!("engine: {} -jar {}", l.java.display(), l.jar.display()),
                Err(e) => log::error!("{e}"),
            }
            let handle = app.handle().clone();
            let sink: EventSink = Arc::new(move |event| match event {
                EngineEvent::Log(line) => {
                    log::info!(target: "engine", "{line}");
                    let _ = handle.emit("engine://log", line);
                }
                EngineEvent::Status(status) => {
                    let _ = handle.emit("engine://status", status);
                }
                EngineEvent::Progress(progress) => {
                    let _ = handle.emit("engine://progress", progress);
                }
            });
            app.manage(Engine::from_launch(launch, sink));
            let data = app.path().app_data_dir().ok();
            app.manage(Recents::load(data.as_ref().map(|d| d.join("recent.json"))));
            app.manage(Projects::new(data.map(|d| d.join("projects"))));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::open_file,
            commands::list_classes,
            commands::decompile_class,
            commands::smali_class,
            commands::node_info,
            commands::find_usages,
            commands::search,
            commands::export_sources,
            commands::cancel_job,
            commands::overview,
            commands::set_code_data,
            commands::list_files,
            commands::read_file,
            commands::load_project,
            commands::save_project,
            commands::write_text_file,
            commands::close_session,
            commands::peek_file,
            commands::recent_files,
            commands::forget_recent,
            commands::replace_recents,
            commands::example_file,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application");

    app.run(|app, event| {
        if let RunEvent::Exit = event {
            tauri::async_runtime::block_on(app.state::<Engine>().shutdown());
        }
    });
}
