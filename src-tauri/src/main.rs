// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // WebKitGTK's DMA-BUF renderer draws at most 60 frames a second, so scrolling and
    // motion stutter on faster screens; the older path follows the display's refresh
    // rate (measured on a 180 Hz screen: scrolling went from 60 to ~160 fps). Set
    // before GTK starts; a value the user set wins.
    #[cfg(target_os = "linux")]
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }
    jreverse_lib::run()
}
