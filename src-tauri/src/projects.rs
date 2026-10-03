//! What the user added to a file (renames, comments, bookmarks), kept as one
//! JSON document per input file in the app data dir. The frontend owns the
//! document's shape; this only stores it, keyed by the file's path.

use std::path::{Path, PathBuf};

use serde_json::Value;

pub struct Projects {
    dir: Option<PathBuf>,
}

impl Projects {
    /// `None` keeps nothing (e.g. when the app data dir is unknown).
    pub fn new(dir: Option<PathBuf>) -> Self {
        Self { dir }
    }

    pub fn load(&self, input: &Path) -> Option<Value> {
        let file = self.file(input)?;
        let bytes = std::fs::read(&file).ok()?;
        match serde_json::from_slice::<Value>(&bytes) {
            Ok(doc) if doc.get("path").and_then(Value::as_str) == input.to_str() => doc.get("data").cloned(),
            Ok(_) => None, // a hash collision: someone else's file
            Err(e) => {
                log::warn!("ignoring unreadable project {}: {e}", file.display());
                None
            }
        }
    }

    /// Saves `data`, or deletes the file when `data` is null.
    pub fn save(&self, input: &Path, data: &Value) -> std::io::Result<()> {
        let Some(file) = self.file(input) else { return Ok(()) };
        if data.is_null() {
            return match std::fs::remove_file(&file) {
                Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e),
                _ => Ok(()),
            };
        }
        std::fs::create_dir_all(file.parent().expect("project files live in a dir"))?;
        let doc = serde_json::json!({ "path": input, "data": data });
        // Write then rename, so a crash never leaves half a file.
        let tmp = file.with_extension("json.tmp");
        std::fs::write(&tmp, serde_json::to_vec_pretty(&doc)?)?;
        std::fs::rename(&tmp, &file)
    }

    fn file(&self, input: &Path) -> Option<PathBuf> {
        let name = format!("{:016x}.json", fnv1a(input.to_string_lossy().as_bytes()));
        Some(self.dir.as_ref()?.join(name))
    }
}

/// FNV-1a: stable across Rust versions, unlike `DefaultHasher`.
fn fnv1a(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325, |h, b| (h ^ u64::from(*b)).wrapping_mul(0x0100_0000_01b3))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("jreverse-projects-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn save_load_and_delete() {
        let dir = temp_dir("roundtrip");
        let projects = Projects::new(Some(dir.clone()));
        let apk = Path::new("/x/app.apk");
        assert_eq!(projects.load(apk), None);
        let data = json!({ "renames": { "a/B": "Login" } });
        projects.save(apk, &data).unwrap();
        assert_eq!(projects.load(apk), Some(data));
        assert_eq!(projects.load(Path::new("/x/other.apk")), None);
        projects.save(apk, &Value::Null).unwrap();
        assert_eq!(projects.load(apk), None);
        projects.save(apk, &Value::Null).unwrap();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn hash_is_stable() {
        assert_eq!(fnv1a(b""), 0xcbf2_9ce4_8422_2325);
        assert_eq!(fnv1a(b"a"), 0xaf63_dc4c_8601_ec8c);
    }
}
