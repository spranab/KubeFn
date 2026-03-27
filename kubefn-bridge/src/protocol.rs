//! Wire protocol for kubefn-bridge.
//!
//! Frame layout (length-prefixed):
//!   [4 bytes: payload length (big-endian u32)]
//!   [1 byte:  message type]
//!   [N bytes: message body (JSON for now, FlatBuffers later)]
//!
//! Message types:
//!   0x01 Register    — runtime registers with broker
//!   0x02 Invoke      — call a function on another runtime
//!   0x03 Result      — function return value
//!   0x04 Alloc       — request shared memory region
//!   0x05 Seal        — mark region as immutable
//!   0x06 Retain      — increment handle refcount
//!   0x07 Release     — decrement handle refcount
//!   0x08 MapFd       — request FD for shared memory region
//!   0x09 Heartbeat   — keep-alive
//!   0x0A Error       — error response
//!   0x10 HeapPublish — publish to HeapExchange (cross-runtime)
//!   0x11 HeapGet     — get from HeapExchange (cross-runtime)
//!   0x12 HeapRemove  — remove from HeapExchange

use serde::{Deserialize, Serialize};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

/// Message type discriminator
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum MsgType {
    Register    = 0x01,
    Invoke      = 0x02,
    Result      = 0x03,
    Alloc       = 0x04,
    Seal        = 0x05,
    Retain      = 0x06,
    Release     = 0x07,
    MapFd       = 0x08,
    Heartbeat   = 0x09,
    Error       = 0x0A,
    HeapPublish = 0x10,
    HeapGet     = 0x11,
    HeapRemove  = 0x12,
}

impl TryFrom<u8> for MsgType {
    type Error = String;
    fn try_from(v: u8) -> core::result::Result<Self, <Self as TryFrom<u8>>::Error> {
        match v {
            0x01 => Ok(Self::Register),
            0x02 => Ok(Self::Invoke),
            0x03 => Ok(Self::Result),
            0x04 => Ok(Self::Alloc),
            0x05 => Ok(Self::Seal),
            0x06 => Ok(Self::Retain),
            0x07 => Ok(Self::Release),
            0x08 => Ok(Self::MapFd),
            0x09 => Ok(Self::Heartbeat),
            0x0A => Ok(Self::Error),
            0x10 => Ok(Self::HeapPublish),
            0x11 => Ok(Self::HeapGet),
            0x12 => Ok(Self::HeapRemove),
            _ => Err(format!("unknown message type: 0x{:02X}", v)),
        }
    }
}

/// A framed message on the wire
#[derive(Debug, Clone)]
pub struct Frame {
    pub msg_type: MsgType,
    pub body: Vec<u8>,
}

impl Frame {
    pub fn new(msg_type: MsgType, body: Vec<u8>) -> Self {
        Self { msg_type, body }
    }

    /// Read a frame from an async reader
    pub async fn read_from<R: AsyncReadExt + Unpin>(reader: &mut R) -> Result<Self, std::io::Error> {
        // Read 4-byte length
        let len = reader.read_u32().await?;
        if len == 0 || len > 64 * 1024 * 1024 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("invalid frame length: {}", len),
            ));
        }

        // Read 1-byte type
        let type_byte = reader.read_u8().await?;
        let msg_type = MsgType::try_from(type_byte).map_err(|e| {
            std::io::Error::new(std::io::ErrorKind::InvalidData, e)
        })?;

        // Read body (length includes the type byte)
        let body_len = (len - 1) as usize;
        let mut body = vec![0u8; body_len];
        if body_len > 0 {
            reader.read_exact(&mut body).await?;
        }

        Ok(Frame { msg_type, body })
    }

    /// Write a frame to an async writer
    pub async fn write_to<W: AsyncWriteExt + Unpin>(&self, writer: &mut W) -> Result<(), std::io::Error> {
        let total_len = 1 + self.body.len(); // type byte + body
        writer.write_u32(total_len as u32).await?;
        writer.write_u8(self.msg_type as u8).await?;
        if !self.body.is_empty() {
            writer.write_all(&self.body).await?;
        }
        writer.flush().await?;
        Ok(())
    }
}

// ── Message payloads ──

#[derive(Debug, Serialize, Deserialize)]
pub struct RegisterMsg {
    pub runtime_id: String,
    pub runtime_type: String, // "jvm", "python", "node"
    pub pid: u32,
    pub functions: Vec<FunctionInfo>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct FunctionInfo {
    pub name: String,
    pub group: String,
    pub input_schema: Option<String>,
    pub output_schema: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct InvokeMsg {
    pub request_id: String,
    pub target_function: String,
    pub target_runtime: Option<String>,
    pub deadline_ms: u64,
    pub trace_id: Option<String>,
    /// For small payloads: inline bytes (base64 encoded in JSON)
    pub payload_inline: Option<String>,
    /// For large payloads: handle to shared memory region
    pub payload_handle: Option<u64>,
    pub payload_format: String, // "json", "arrow", "flatbuf", "bytes"
}

#[derive(Debug, Serialize, Deserialize)]
pub struct ResultMsg {
    pub request_id: String,
    pub success: bool,
    pub payload_inline: Option<String>,
    pub payload_handle: Option<u64>,
    pub payload_format: String,
    pub duration_us: u64,
    pub error: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct AllocMsg {
    pub size: u64,
    pub format: String,
    pub schema_id: Option<String>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct AllocResponse {
    pub handle_id: u64,
    pub offset: u64,
    pub length: u64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct HeapPublishMsg {
    pub key: String,
    pub payload_inline: Option<String>,
    pub payload_handle: Option<u64>,
    pub payload_format: String,
    pub schema_id: Option<String>,
    pub ttl_ms: Option<u64>,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct HeapGetMsg {
    pub key: String,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct HeapGetResponse {
    pub found: bool,
    pub payload_inline: Option<String>,
    pub payload_handle: Option<u64>,
    pub payload_format: Option<String>,
    pub producer_runtime: Option<String>,
}
