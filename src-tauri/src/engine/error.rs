use serde::ser::SerializeStruct;
use serde::{Serialize, Serializer};

#[derive(Debug, Clone, thiserror::Error)]
pub enum EngineError {
    #[error("engine not found: {0}")]
    NotFound(String),
    #[error("failed to start the engine: {0}")]
    Spawn(String),
    #[error("engine did not start: {0}")]
    Handshake(String),
    #[error("engine speaks protocol {found}, expected {expected}")]
    ProtocolMismatch { found: u64, expected: u64 },
    #[error("the engine stopped unexpectedly")]
    Crashed,
    #[error("bad message from engine: {0}")]
    BadResponse(String),
    /// An error the engine reported, e.g. `NO_CLASS`.
    #[error("{message}")]
    Remote { code: String, message: String },
}

impl EngineError {
    pub fn code(&self) -> &str {
        match self {
            Self::NotFound(_) => "ENGINE_NOT_FOUND",
            Self::Spawn(_) => "ENGINE_SPAWN_FAILED",
            Self::Handshake(_) => "ENGINE_HANDSHAKE_FAILED",
            Self::ProtocolMismatch { .. } => "ENGINE_PROTOCOL_MISMATCH",
            Self::Crashed => "ENGINE_CRASHED",
            Self::BadResponse(_) => "ENGINE_BAD_RESPONSE",
            Self::Remote { code, .. } => code,
        }
    }

    pub(crate) fn remote(code: &str, message: impl Into<String>) -> Self {
        Self::Remote { code: code.into(), message: message.into() }
    }
}

/// Reaches the frontend as `{ code, message }`.
impl Serialize for EngineError {
    fn serialize<S: Serializer>(&self, serializer: S) -> Result<S::Ok, S::Error> {
        let mut s = serializer.serialize_struct("EngineError", 2)?;
        s.serialize_field("code", self.code())?;
        s.serialize_field("message", &self.to_string())?;
        s.end()
    }
}
