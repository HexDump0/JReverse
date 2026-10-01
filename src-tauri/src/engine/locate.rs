use std::path::{Path, PathBuf};

use super::EngineError;

const JAVA_ENV: &str = "JREVERSE_JAVA";
const JAR_ENV: &str = "JREVERSE_ENGINE_JAR";
const EXTRA_ARGS_ENV: &str = "JREVERSE_ENGINE_JVM_ARGS";

const JVM_ARGS: &[&str] = &[
    "-XX:MaxRAMPercentage=50",
    "-Xss8m", // jadx recurses deeply on large methods
    "-Djava.awt.headless=true",
    "-Dfile.encoding=UTF-8",
    "-Dstdout.encoding=UTF-8",
    "-Dstderr.encoding=UTF-8",
];

/// How to start the engine: which `java`, which jar, which flags.
#[derive(Debug, Clone)]
pub struct Launch {
    pub java: PathBuf,
    pub jar: PathBuf,
    pub jvm_args: Vec<String>,
}

impl Launch {
    /// Uses the runtime bundled under `<resources>/engine/`, unless
    /// `JREVERSE_JAVA` / `JREVERSE_ENGINE_JAR` point elsewhere.
    /// `JREVERSE_ENGINE_JVM_ARGS` appends extra flags, e.g. a debug agent.
    pub fn resolve(resource_dir: Option<&Path>) -> Result<Self, EngineError> {
        let bundled = resource_dir.map(|dir| dir.join("engine"));
        let java = from_env(JAVA_ENV)
            .or_else(|| bundled.as_ref().map(|d| d.join("runtime").join("bin").join(java_exe())))
            .ok_or_else(|| EngineError::NotFound("no resource directory".into()))?;
        let jar = from_env(JAR_ENV)
            .or_else(|| bundled.as_ref().map(|d| d.join("engine.jar")))
            .ok_or_else(|| EngineError::NotFound("no resource directory".into()))?;
        for path in [&java, &jar] {
            if !path.is_file() {
                return Err(EngineError::NotFound(format!(
                    "{} is missing (run `pnpm engine` to build it)",
                    path.display()
                )));
            }
        }
        let mut jvm_args: Vec<String> = JVM_ARGS.iter().map(|s| s.to_string()).collect();
        if let Ok(extra) = std::env::var(EXTRA_ARGS_ENV) {
            jvm_args.extend(extra.split_whitespace().map(String::from));
        }
        Ok(Self { java, jar, jvm_args })
    }
}

fn from_env(name: &str) -> Option<PathBuf> {
    std::env::var_os(name).filter(|v| !v.is_empty()).map(PathBuf::from)
}

fn java_exe() -> &'static str {
    if cfg!(windows) {
        "java.exe"
    } else {
        "java"
    }
}
