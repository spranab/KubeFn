//! KubeFn Bridge — Cross-Language Broker
//!
//! UDS control plane + shared memory data plane.
//! Manages function invocation, shared memory handles,
//! and lifecycle across JVM, Python, and Node.js runtimes.
//!
//! Architecture:
//!   Runtime → UDS → Broker → UDS → Runtime
//!                    ↕
//!              Shared Memory (mmap)
//!
//! Small payloads (<64KB): inline in UDS frame
//! Large payloads (>=64KB): shared memory handle

mod protocol;
mod broker;
mod shared_mem;

use broker::Broker;
use tracing::{info, error};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "kubefn_bridge=info".into()),
        )
        .init();

    let sock_path = std::env::var("KUBEFN_BRIDGE_SOCK")
        .unwrap_or_else(|_| "/tmp/kubefn-bridge.sock".to_string());

    let admin_port: u16 = std::env::var("KUBEFN_BRIDGE_ADMIN_PORT")
        .unwrap_or_else(|_| "9999".to_string())
        .parse()?;

    info!("KubeFn Bridge v0.1.0 — Cross-Language Shared Memory Broker");
    info!("UDS: {}", sock_path);
    info!("Admin: http://0.0.0.0:{}", admin_port);

    let broker = Broker::new();

    tokio::select! {
        r = broker.serve_uds(&sock_path) => {
            if let Err(e) = r { error!("UDS server error: {}", e); }
        }
        r = broker.serve_admin(admin_port) => {
            if let Err(e) = r { error!("Admin server error: {}", e); }
        }
        _ = tokio::signal::ctrl_c() => {
            info!("Shutting down bridge...");
        }
    }

    Ok(())
}
