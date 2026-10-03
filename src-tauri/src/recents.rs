//! Files opened before, newest first, kept as JSON in the app data dir.

use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};

const MAX: usize = 50;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Recent {
    pub path: PathBuf,
    pub kind: String,
    pub class_count: u64,
    pub size: u64,
    /// Unix time in milliseconds.
    pub opened_at: u64,
}

/// What the frontend gets: a recent file plus whether it is still there.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecentView {
    #[serde(flatten)]
    pub recent: Recent,
    pub missing: bool,
}

pub struct Recents {
    file: Option<PathBuf>,
    list: Mutex<Vec<Recent>>,
}

impl Recents {
    /// Loads `file`; a missing or unreadable one starts an empty list.
    /// `None` keeps the list in memory only.
    pub fn load(file: Option<PathBuf>) -> Self {
        let list = file
            .as_deref()
            .and_then(|f| std::fs::read(f).ok())
            .and_then(|bytes| match serde_json::from_slice(&bytes) {
                Ok(list) => Some(list),
                Err(e) => {
                    log::warn!("ignoring unreadable recent files list: {e}");
                    None
                }
            })
            .unwrap_or_default();
        Self { file, list: Mutex::new(list) }
    }

    pub fn list(&self) -> Vec<RecentView> {
        self.list
            .lock()
            .unwrap()
            .iter()
            .map(|r| RecentView { missing: !r.path.is_file(), recent: r.clone() })
            .collect()
    }

    /// Moves `path` to the top, replacing what was known about it.
    pub fn record(&self, path: &Path, kind: &str, class_count: u64) {
        let size = std::fs::metadata(path).map(|m| m.len()).unwrap_or(0);
        let entry = Recent { path: path.to_path_buf(), kind: kind.into(), class_count, size, opened_at: now_ms() };
        self.update(|list| {
            list.retain(|r| r.path != entry.path);
            list.insert(0, entry);
        });
    }

    pub fn forget(&self, path: &Path) {
        self.update(|list| list.retain(|r| r.path != path));
    }

    /// Replaces the whole list; used to undo a remove or clear.
    pub fn replace(&self, entries: Vec<Recent>) {
        self.update(|list| *list = entries);
    }

    fn update(&self, f: impl FnOnce(&mut Vec<Recent>)) {
        let mut list = self.list.lock().unwrap();
        f(&mut list);
        list.truncate(MAX);
        if let Some(file) = &self.file {
            if let Err(e) = save(file, &list) {
                log::error!("could not save recent files to {}: {e}", file.display());
            }
        }
    }
}

/// Writes to a temporary file first so a crash can't leave half a list.
fn save(file: &Path, list: &[Recent]) -> std::io::Result<()> {
    if let Some(dir) = file.parent() {
        std::fs::create_dir_all(dir)?;
    }
    let tmp = file.with_extension("json.tmp");
    std::fs::write(&tmp, serde_json::to_vec_pretty(list)?)?;
    std::fs::rename(tmp, file)
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn records_newest_first_and_persists() {
        let dir = std::env::temp_dir().join(format!("jreverse-recents-{}", std::process::id()));
        let file = dir.join("recent.json");
        let a = dir.join("a.jar");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(&a, b"PK").unwrap();

        let recents = Recents::load(Some(file.clone()));
        recents.record(&a, "jar", 3);
        recents.record(&dir.join("gone.apk"), "apk", 10);
        recents.record(&a, "jar", 4);

        let list = Recents::load(Some(file)).list();
        assert_eq!(list.len(), 2);
        assert_eq!((list[0].recent.path.clone(), list[0].recent.class_count, list[0].recent.size), (a, 4, 2));
        assert!(!list[0].missing);
        assert!(list[1].missing);

        recents.forget(&dir.join("gone.apk"));
        assert_eq!(recents.list().len(), 1);
        std::fs::remove_dir_all(dir).ok();
    }
}
