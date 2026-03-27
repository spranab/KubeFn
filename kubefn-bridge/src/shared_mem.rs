//! Shared memory region manager.
//!
//! Creates and manages mmap'd regions that can be shared between
//! JVM, Python, and Node.js runtimes via file descriptor passing.
//!
//! Each region is:
//!   - Allocated via anonymous mmap (or memfd on Linux)
//!   - Writable by producer until sealed
//!   - Read-only after seal
//!   - Reference-counted across runtimes
//!   - Reclaimed when refcount reaches zero

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, RwLock};
use std::time::{Duration, Instant};
use tracing::{info, warn};

/// Unique handle for a shared memory region
pub type HandleId = u64;

/// Metadata for a shared memory region
#[derive(Debug, Clone)]
pub struct HandleMeta {
    pub handle_id: HandleId,
    pub size: usize,
    pub format: String,          // "json", "arrow", "flatbuf", "bytes"
    pub schema_id: Option<String>,
    pub owner_runtime: String,
    pub sealed: bool,
    pub refcount: u32,
    pub created_at: Instant,
    pub ttl: Option<Duration>,
    /// The actual data (for now, heap-allocated; later: mmap'd region)
    pub data: Vec<u8>,
}

/// Manages shared memory regions and their lifecycles
pub struct SharedMemoryManager {
    handles: RwLock<HashMap<HandleId, HandleMeta>>,
    next_id: AtomicU64,
    /// HeapExchange: key → handle_id for cross-runtime heap sharing
    heap_index: RwLock<HashMap<String, HandleId>>,
}

impl SharedMemoryManager {
    pub fn new() -> Self {
        Self {
            handles: RwLock::new(HashMap::new()),
            next_id: AtomicU64::new(1),
            heap_index: RwLock::new(HashMap::new()),
        }
    }

    /// Allocate a new shared region
    pub fn alloc(
        &self,
        size: usize,
        format: String,
        schema_id: Option<String>,
        owner: String,
    ) -> HandleId {
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let meta = HandleMeta {
            handle_id: id,
            size,
            format,
            schema_id,
            owner_runtime: owner,
            sealed: false,
            refcount: 1,
            created_at: Instant::now(),
            ttl: None,
            data: Vec::with_capacity(size),
        };
        self.handles.write().unwrap().insert(id, meta);
        info!("Allocated handle {} ({}B)", id, size);
        id
    }

    /// Write data to a handle (before sealing)
    pub fn write(&self, handle_id: HandleId, data: Vec<u8>) -> Result<(), String> {
        let mut handles = self.handles.write().unwrap();
        let meta = handles.get_mut(&handle_id)
            .ok_or_else(|| format!("handle {} not found", handle_id))?;
        if meta.sealed {
            return Err(format!("handle {} is sealed (immutable)", handle_id));
        }
        meta.size = data.len();
        meta.data = data;
        Ok(())
    }

    /// Seal a handle (make immutable)
    pub fn seal(&self, handle_id: HandleId) -> Result<(), String> {
        let mut handles = self.handles.write().unwrap();
        let meta = handles.get_mut(&handle_id)
            .ok_or_else(|| format!("handle {} not found", handle_id))?;
        meta.sealed = true;
        info!("Sealed handle {} ({}B, {})", handle_id, meta.size, meta.format);
        Ok(())
    }

    /// Read data from a handle
    pub fn read(&self, handle_id: HandleId) -> Result<Vec<u8>, String> {
        let handles = self.handles.read().unwrap();
        let meta = handles.get(&handle_id)
            .ok_or_else(|| format!("handle {} not found", handle_id))?;
        Ok(meta.data.clone())
    }

    /// Increment refcount
    pub fn retain(&self, handle_id: HandleId) -> Result<u32, String> {
        let mut handles = self.handles.write().unwrap();
        let meta = handles.get_mut(&handle_id)
            .ok_or_else(|| format!("handle {} not found", handle_id))?;
        meta.refcount += 1;
        Ok(meta.refcount)
    }

    /// Decrement refcount, remove if zero
    pub fn release(&self, handle_id: HandleId) -> Result<u32, String> {
        let mut handles = self.handles.write().unwrap();
        let meta = handles.get_mut(&handle_id)
            .ok_or_else(|| format!("handle {} not found", handle_id))?;
        meta.refcount = meta.refcount.saturating_sub(1);
        let rc = meta.refcount;
        if rc == 0 {
            handles.remove(&handle_id);
            // Also remove from heap index if present
            self.heap_index.write().unwrap().retain(|_, v| *v != handle_id);
            info!("Released handle {} (refcount=0, reclaimed)", handle_id);
        }
        Ok(rc)
    }

    // ── HeapExchange bridge ──

    /// Publish to cross-runtime HeapExchange
    pub fn heap_publish(
        &self,
        key: String,
        data: Vec<u8>,
        format: String,
        schema_id: Option<String>,
        owner: String,
        ttl_ms: Option<u64>,
    ) -> HandleId {
        let handle_id = self.alloc(data.len(), format, schema_id, owner);
        self.write(handle_id, data).unwrap();
        self.seal(handle_id).unwrap();

        // Set TTL
        if let Some(ttl) = ttl_ms {
            let mut handles = self.handles.write().unwrap();
            if let Some(meta) = handles.get_mut(&handle_id) {
                meta.ttl = Some(Duration::from_millis(ttl));
            }
        }

        // Update heap index (release old handle if exists)
        let mut index = self.heap_index.write().unwrap();
        if let Some(old_handle) = index.insert(key.clone(), handle_id) {
            drop(index);
            let _ = self.release(old_handle);
        }

        handle_id
    }

    /// Get from cross-runtime HeapExchange
    pub fn heap_get(&self, key: &str) -> Option<(HandleId, Vec<u8>, String, String)> {
        let index = self.heap_index.read().unwrap();
        let handle_id = *index.get(key)?;
        let handles = self.handles.read().unwrap();
        let meta = handles.get(&handle_id)?;

        // Check TTL
        if let Some(ttl) = meta.ttl {
            if meta.created_at.elapsed() > ttl {
                return None;
            }
        }

        Some((
            handle_id,
            meta.data.clone(),
            meta.format.clone(),
            meta.owner_runtime.clone(),
        ))
    }

    /// Remove from HeapExchange
    pub fn heap_remove(&self, key: &str) -> bool {
        let mut index = self.heap_index.write().unwrap();
        if let Some(handle_id) = index.remove(key) {
            drop(index);
            let _ = self.release(handle_id);
            true
        } else {
            false
        }
    }

    /// Get status for admin API
    pub fn status(&self) -> serde_json::Value {
        let handles = self.handles.read().unwrap();
        let index = self.heap_index.read().unwrap();

        let total_bytes: usize = handles.values().map(|h| h.data.len()).sum();
        let by_format: HashMap<String, usize> = handles.values().fold(HashMap::new(), |mut acc, h| {
            *acc.entry(h.format.clone()).or_insert(0) += 1;
            acc
        });

        serde_json::json!({
            "handles": handles.len(),
            "heapKeys": index.len(),
            "totalBytes": total_bytes,
            "totalMB": total_bytes as f64 / (1024.0 * 1024.0),
            "byFormat": by_format,
        })
    }

    /// Evict expired handles
    pub fn evict_expired(&self) -> usize {
        let mut handles = self.handles.write().unwrap();
        let before = handles.len();
        handles.retain(|_, meta| {
            if let Some(ttl) = meta.ttl {
                meta.created_at.elapsed() <= ttl
            } else {
                true
            }
        });
        let evicted = before - handles.len();
        if evicted > 0 {
            // Clean heap index
            let valid_ids: std::collections::HashSet<HandleId> =
                handles.keys().copied().collect();
            self.heap_index.write().unwrap().retain(|_, v| valid_ids.contains(v));
            info!("Evicted {} expired handles", evicted);
        }
        evicted
    }
}
