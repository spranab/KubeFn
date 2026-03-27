//! The Broker — central coordinator for cross-runtime communication.
//!
//! Responsibilities:
//!   - Accept UDS connections from runtimes (JVM, Python, Node)
//!   - Route invocations between runtimes
//!   - Manage shared memory handles
//!   - Bridge HeapExchange across languages
//!   - Serve admin API (status, metrics)
//!   - Evict expired handles

use std::collections::HashMap;
use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, UnixListener, UnixStream};
use tokio::sync::RwLock;
use tracing::{info, warn, error};

use crate::protocol::*;
use crate::shared_mem::SharedMemoryManager;

/// A connected runtime
#[derive(Debug)]
struct RuntimeConn {
    runtime_id: String,
    runtime_type: String,
    functions: Vec<FunctionInfo>,
}

pub struct Broker {
    mem: Arc<SharedMemoryManager>,
    runtimes: Arc<RwLock<HashMap<String, RuntimeConn>>>,
    /// function_name → runtime_id (routing table)
    routes: Arc<RwLock<HashMap<String, String>>>,
}

impl Broker {
    pub fn new() -> Self {
        Self {
            mem: Arc::new(SharedMemoryManager::new()),
            runtimes: Arc::new(RwLock::new(HashMap::new())),
            routes: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Serve UDS connections from runtimes
    pub async fn serve_uds(&self, path: &str) -> Result<(), Box<dyn std::error::Error>> {
        // Remove stale socket
        let _ = std::fs::remove_file(path);

        let listener = UnixListener::bind(path)?;
        info!("UDS server listening on {}", path);

        // Spawn eviction task
        let mem = self.mem.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(tokio::time::Duration::from_secs(10)).await;
                mem.evict_expired();
            }
        });

        loop {
            let (stream, _addr) = listener.accept().await?;
            let mem = self.mem.clone();
            let runtimes = self.runtimes.clone();
            let routes = self.routes.clone();

            tokio::spawn(async move {
                if let Err(e) = handle_connection(stream, mem, runtimes, routes).await {
                    warn!("Connection error: {}", e);
                }
            });
        }
    }

    /// Serve admin HTTP API
    pub async fn serve_admin(&self, port: u16) -> Result<(), Box<dyn std::error::Error>> {
        let listener = TcpListener::bind(format!("0.0.0.0:{}", port)).await?;
        info!("Admin server listening on port {}", port);

        loop {
            let (mut stream, _) = listener.accept().await?;
            let mem = self.mem.clone();
            let runtimes = self.runtimes.clone();
            let routes = self.routes.clone();

            tokio::spawn(async move {
                let mut buf = vec![0u8; 4096];
                let n = stream.read(&mut buf).await.unwrap_or(0);
                let request = String::from_utf8_lossy(&buf[..n]);

                let (status, body) = if request.contains("GET /status") {
                    let rt = runtimes.read().await;
                    let r = routes.read().await;
                    let status = serde_json::json!({
                        "bridge": "kubefn-bridge v0.1.0",
                        "runtimes": rt.keys().collect::<Vec<_>>(),
                        "routes": r.len(),
                        "memory": mem.status(),
                    });
                    ("200 OK", serde_json::to_string_pretty(&status).unwrap())
                } else if request.contains("GET /health") {
                    ("200 OK", r#"{"status":"ok"}"#.to_string())
                } else {
                    ("404 Not Found", r#"{"error":"not found"}"#.to_string())
                };

                let response = format!(
                    "HTTP/1.1 {}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    status, body.len(), body
                );
                let _ = stream.write_all(response.as_bytes()).await;
            });
        }
    }
}

/// Handle a single runtime connection
async fn handle_connection(
    mut stream: UnixStream,
    mem: Arc<SharedMemoryManager>,
    runtimes: Arc<RwLock<HashMap<String, RuntimeConn>>>,
    routes: Arc<RwLock<HashMap<String, String>>>,
) -> Result<(), Box<dyn std::error::Error>> {
    let (mut reader, mut writer) = stream.into_split();
    let mut runtime_id: Option<String> = None;

    loop {
        let frame = match Frame::read_from(&mut reader).await {
            Ok(f) => f,
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => break,
            Err(e) => return Err(e.into()),
        };

        let response = match frame.msg_type {
            MsgType::Register => {
                let msg: RegisterMsg = serde_json::from_slice(&frame.body)?;
                info!("Runtime registered: {} ({}), {} functions",
                    msg.runtime_id, msg.runtime_type, msg.functions.len());

                // Update routing table
                let mut r = routes.write().await;
                for func in &msg.functions {
                    r.insert(func.name.clone(), msg.runtime_id.clone());
                }

                let conn = RuntimeConn {
                    runtime_id: msg.runtime_id.clone(),
                    runtime_type: msg.runtime_type,
                    functions: msg.functions,
                };
                runtime_id = Some(msg.runtime_id.clone());
                runtimes.write().await.insert(msg.runtime_id, conn);

                Frame::new(MsgType::Result, serde_json::to_vec(&serde_json::json!({
                    "request_id": "register",
                    "success": true,
                    "payload_inline": null,
                    "payload_handle": null,
                    "payload_format": "json",
                    "duration_us": 0,
                    "error": null
                }))?)
            }

            MsgType::HeapPublish => {
                let msg: HeapPublishMsg = serde_json::from_slice(&frame.body)?;
                let owner = runtime_id.clone().unwrap_or_else(|| "unknown".to_string());
                let data = msg.payload_inline
                    .map(|s| s.into_bytes())
                    .unwrap_or_default();

                let handle_id = mem.heap_publish(
                    msg.key.clone(),
                    data,
                    msg.payload_format,
                    msg.schema_id,
                    owner,
                    msg.ttl_ms,
                );

                info!("HeapPublish: key='{}' handle={}", msg.key, handle_id);

                Frame::new(MsgType::Result, serde_json::to_vec(&serde_json::json!({
                    "request_id": "heap_publish",
                    "success": true,
                    "payload_inline": null,
                    "payload_handle": handle_id,
                    "payload_format": "json",
                    "duration_us": 0,
                    "error": null
                }))?)
            }

            MsgType::HeapGet => {
                let msg: HeapGetMsg = serde_json::from_slice(&frame.body)?;

                let response = if let Some((handle_id, data, format, producer)) = mem.heap_get(&msg.key) {
                    serde_json::json!({
                        "found": true,
                        "payload_inline": String::from_utf8_lossy(&data),
                        "payload_handle": handle_id,
                        "payload_format": format,
                        "producer_runtime": producer
                    })
                } else {
                    serde_json::json!({
                        "found": false,
                        "payload_inline": null,
                        "payload_handle": null,
                        "payload_format": null,
                        "producer_runtime": null
                    })
                };

                Frame::new(MsgType::HeapGet, serde_json::to_vec(&response)?)
            }

            MsgType::HeapRemove => {
                let msg: HeapGetMsg = serde_json::from_slice(&frame.body)?;
                let removed = mem.heap_remove(&msg.key);

                Frame::new(MsgType::Result, serde_json::to_vec(&serde_json::json!({
                    "request_id": "heap_remove",
                    "success": removed,
                    "payload_inline": null,
                    "payload_handle": null,
                    "payload_format": "json",
                    "duration_us": 0,
                    "error": null
                }))?)
            }

            MsgType::Heartbeat => {
                Frame::new(MsgType::Heartbeat, vec![])
            }

            MsgType::Alloc => {
                let msg: AllocMsg = serde_json::from_slice(&frame.body)?;
                let owner = runtime_id.clone().unwrap_or_else(|| "unknown".to_string());
                let handle_id = mem.alloc(msg.size as usize, msg.format, msg.schema_id, owner);

                Frame::new(MsgType::Result, serde_json::to_vec(&AllocResponse {
                    handle_id,
                    offset: 0,
                    length: msg.size,
                })?)
            }

            MsgType::Seal => {
                let handle_id: u64 = serde_json::from_slice(&frame.body)?;
                let result = mem.seal(handle_id);

                Frame::new(MsgType::Result, serde_json::to_vec(&serde_json::json!({
                    "request_id": "seal",
                    "success": result.is_ok(),
                    "error": result.err()
                }))?)
            }

            MsgType::Retain => {
                let handle_id: u64 = serde_json::from_slice(&frame.body)?;
                let rc = mem.retain(handle_id).unwrap_or(0);
                Frame::new(MsgType::Result, serde_json::to_vec(&serde_json::json!({"refcount": rc}))?)
            }

            MsgType::Release => {
                let handle_id: u64 = serde_json::from_slice(&frame.body)?;
                let rc = mem.release(handle_id).unwrap_or(0);
                Frame::new(MsgType::Result, serde_json::to_vec(&serde_json::json!({"refcount": rc}))?)
            }

            _ => {
                Frame::new(MsgType::Error, serde_json::to_vec(&serde_json::json!({
                    "error": format!("unhandled message type: {:?}", frame.msg_type)
                }))?)
            }
        };

        response.write_to(&mut writer).await?;
    }

    // Cleanup on disconnect
    if let Some(id) = &runtime_id {
        info!("Runtime disconnected: {}", id);
        runtimes.write().await.remove(id);
        routes.write().await.retain(|_, v| v != id);
    }

    Ok(())
}
