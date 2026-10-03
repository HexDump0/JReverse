//! A quick look at a file before opening it: its first bytes and what kind of
//! input it is. Follows the engine's `InputDetector` rules, so a file that peeks
//! as supported is one the engine will accept, and adds a friendlier reason
//! for the ones it rejects ("this is a PDF").

use std::fs::File;
use std::io::{self, Read, Seek, SeekFrom};
use std::path::Path;

use serde::Serialize;

const HEAD_LEN: usize = 16;
const NOT_JAVA: &str = "Supported files are APK, AAB, AAR, JAR, WAR, DEX and class files.";

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Peek {
    pub name: String,
    pub size: u64,
    /// Up to the first 16 bytes.
    pub head: Vec<u8>,
    /// `apk`, `aar`, `jar`, `dex` or `class`, the engine's names. `None` when unsupported.
    pub kind: Option<String>,
    /// Short description of what was found, e.g. "3 DEX files".
    pub detail: Option<String>,
    /// Classes counted without a full open: top-level classes in a JAR, class
    /// definitions in a DEX. `None` where peeking can't tell.
    pub classes: Option<u64>,
    /// Why the file can't be opened.
    pub problem: Option<Problem>,
}

#[derive(Debug, Clone, Serialize)]
pub struct Problem {
    pub title: String,
    pub text: String,
}

enum Found {
    Kind { kind: &'static str, detail: String, classes: Option<u64> },
    Problem(&'static str, String),
}

pub fn peek(path: &Path) -> io::Result<Peek> {
    let mut file = File::open(path)?;
    let size = file.metadata()?.len();
    let mut head = Vec::with_capacity(HEAD_LEN);
    file.by_ref().take(HEAD_LEN as u64).read_to_end(&mut head)?;

    let found = detect(&mut file, size, &head)?;
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    let mut peek = Peek { name, size, head, kind: None, detail: None, classes: None, problem: None };
    match found {
        Found::Kind { kind, detail, classes } => {
            peek.kind = Some(kind.into());
            peek.detail = Some(detail);
            peek.classes = classes;
        }
        Found::Problem(title, text) => peek.problem = Some(Problem { title: title.into(), text }),
    }
    Ok(peek)
}

fn detect(file: &mut File, size: u64, b: &[u8]) -> io::Result<Found> {
    if b.len() < 4 {
        return Ok(Found::Problem("Too small to be a binary", format!("It's {size} bytes. {NOT_JAVA}")));
    }
    Ok(match &b[..4] {
        b"dex\n" => {
            let classes = read_u32_at(file, 0x60)?;
            Found::Kind {
                kind: "dex",
                detail: classes.map_or("DEX file".into(), |n| format!("{} class definitions", group(n as u64))),
                classes: classes.map(u64::from),
            }
        }
        [0xca, 0xfe, 0xba, 0xbe] => {
            let detail = match b.get(6..8) {
                Some(&[hi, lo]) if u16::from_be_bytes([hi, lo]) > 44 => format!("Java {}", u16::from_be_bytes([hi, lo]) - 44),
                _ => "Class file".into(),
            };
            Found::Kind { kind: "class", detail, classes: Some(1) }
        }
        [b'P', b'K', 3, 4] => detect_zip(file),
        b"%PDF" => Found::Problem("This is a PDF", NOT_JAVA.into()),
        [0x89, b'P', b'N', b'G'] => Found::Problem("This is a PNG image", NOT_JAVA.into()),
        [0x7f, b'E', b'L', b'F'] => Found::Problem(
            "This is a native ELF binary",
            "JReverse reads Java bytecode and DEX. It doesn't disassemble native libraries.".into(),
        ),
        [b'M', b'Z', ..] => Found::Problem("This is a Windows executable", NOT_JAVA.into()),
        _ => Found::Problem("Not a Java or Android binary", NOT_JAVA.into()),
    })
}

fn detect_zip(file: &mut File) -> Found {
    let names: Vec<String> = match zip::ZipArchive::new(&mut *file) {
        Ok(zip) => zip.file_names().map(str::to_owned).collect(),
        Err(e) => return Found::Problem("Damaged archive", format!("It starts like a ZIP, but {e}.")),
    };
    let dex = names.iter().filter(|n| is_root_dex(n)).count();
    if dex > 0 {
        let detail = if dex == 1 { "1 DEX file".into() } else { format!("{dex} DEX files") };
        return Found::Kind { kind: "apk", detail, classes: None };
    }
    // An App Bundle keeps each module's code under `<module>/dex/`.
    let mut modules: Vec<&str> = names.iter().filter(|n| is_module_dex(n)).filter_map(|n| n.split('/').next()).collect();
    modules.sort_unstable();
    modules.dedup();
    if !modules.is_empty() {
        let detail = if modules.len() == 1 { "App Bundle".into() } else { format!("App Bundle, {} modules", modules.len()) };
        return Found::Kind { kind: "aab", detail, classes: None };
    }
    if names.iter().any(|n| n == "classes.jar") {
        return Found::Kind { kind: "aar", detail: "classes.jar".into(), classes: None };
    }
    let classes = names.iter().filter(|n| n.ends_with(".class")).count();
    if classes > 0 {
        // Inner classes and module-info aren't what people count as classes.
        let top = names
            .iter()
            .filter(|n| n.ends_with(".class") && !n.contains('$') && !n.ends_with("module-info.class"))
            .count() as u64;
        return Found::Kind { kind: "jar", detail: format!("{} classes", group(top)), classes: Some(top) };
    }
    Found::Problem("A ZIP with no code in it", format!("It has {} entries but no DEX or class files.", names.len()))
}

/// `classes.dex`, `classes2.dex`, ... at the archive root, as the engine matches them.
fn is_root_dex(name: &str) -> bool {
    name.strip_prefix("classes")
        .and_then(|rest| rest.strip_suffix(".dex"))
        .is_some_and(|n| n.bytes().all(|c| c.is_ascii_digit()))
}

/// `base/dex/classes.dex`, `feature/dex/classes2.dex`, ...
fn is_module_dex(name: &str) -> bool {
    let mut parts = name.split('/');
    matches!((parts.next(), parts.next(), parts.next(), parts.next()), (Some(m), Some("dex"), Some(f), None) if !m.is_empty() && is_root_dex(f))
}

fn read_u32_at(file: &mut File, offset: u64) -> io::Result<Option<u32>> {
    file.seek(SeekFrom::Start(offset))?;
    let mut buf = [0u8; 4];
    match file.read_exact(&mut buf) {
        Ok(()) => Ok(Some(u32::from_le_bytes(buf))),
        Err(e) if e.kind() == io::ErrorKind::UnexpectedEof => Ok(None),
        Err(e) => Err(e),
    }
}

/// 2976 -> "2,976"
fn group(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::with_capacity(s.len() + s.len() / 3);
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i).is_multiple_of(3) {
            out.push(',');
        }
        out.push(c);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn peek_bytes(bytes: &[u8]) -> Peek {
        let dir = std::env::temp_dir().join(format!("jreverse-peek-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join(format!("f{}", bytes.len()));
        std::fs::File::create(&path).unwrap().write_all(bytes).unwrap();
        let p = peek(&path).unwrap();
        std::fs::remove_file(&path).ok();
        p
    }

    fn zip_with(names: &[&str]) -> Vec<u8> {
        let mut buf = io::Cursor::new(Vec::new());
        let mut zip = zip::ZipWriter::new(&mut buf);
        for name in names {
            zip.start_file(*name, zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Stored))
                .unwrap();
            zip.write_all(b"x").unwrap();
        }
        zip.finish().unwrap();
        buf.into_inner()
    }

    #[test]
    fn detects_kinds() {
        let mut dex = b"dex\n035\0".to_vec();
        dex.resize(0x70, 0);
        dex[0x60..0x64].copy_from_slice(&2976u32.to_le_bytes());
        let p = peek_bytes(&dex);
        assert_eq!(p.kind.as_deref(), Some("dex"));
        assert_eq!(p.detail.as_deref(), Some("2,976 class definitions"));
        assert_eq!(p.head.len(), 16);

        let p = peek_bytes(&[0xca, 0xfe, 0xba, 0xbe, 0, 0, 0, 65]);
        assert_eq!(p.kind.as_deref(), Some("class"));
        assert_eq!(p.detail.as_deref(), Some("Java 21"));

        let p = peek_bytes(&zip_with(&["classes.dex", "classes2.dex", "AndroidManifest.xml"]));
        assert_eq!((p.kind.as_deref(), p.detail.as_deref()), (Some("apk"), Some("2 DEX files")));

        let p = peek_bytes(&zip_with(&["BundleConfig.pb", "base/dex/classes.dex", "base/dex/classes2.dex", "pay/dex/classes.dex"]));
        assert_eq!((p.kind.as_deref(), p.detail.as_deref()), (Some("aab"), Some("App Bundle, 2 modules")));

        let p = peek_bytes(&zip_with(&["classes.jar", "AndroidManifest.xml"]));
        assert_eq!(p.kind.as_deref(), Some("aar"));

        let p = peek_bytes(&zip_with(&["a/B.class", "a/B$1.class", "module-info.class"]));
        assert_eq!((p.kind.as_deref(), p.classes), (Some("jar"), Some(1)));
    }

    #[test]
    fn explains_unsupported() {
        let p = peek_bytes(b"%PDF-1.7 hello");
        assert!(p.kind.is_none());
        assert_eq!(p.problem.unwrap().title, "This is a PDF");

        assert_eq!(peek_bytes(b"ab").problem.unwrap().title, "Too small to be a binary");
        assert_eq!(peek_bytes(&zip_with(&["readme.txt"])).problem.unwrap().title, "A ZIP with no code in it");
        assert_eq!(peek_bytes(b"PK\x03\x04garbage").problem.unwrap().title, "Damaged archive");
    }

    #[test]
    fn groups_digits() {
        assert_eq!(group(7), "7");
        assert_eq!(group(1000), "1,000");
        assert_eq!(group(1234567), "1,234,567");
    }
}
