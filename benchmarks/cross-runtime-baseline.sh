#!/usr/bin/env bash
# Cross-Runtime Baseline Benchmark
# Measures: HTTP latency between runtimes (JVM, Python, Node) in same pod
# Purpose: Establish baseline before building kubefn-bridge (UDS + shared memory)
#
# Usage: ./benchmarks/cross-runtime-baseline.sh

set -euo pipefail

BOLD='\033[1m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# ── Payload sizes to test ──
PAYLOADS=(
    "64"        # 64 bytes  - tiny (function metadata)
    "1024"      # 1 KB      - small (PricingResult)
    "65536"     # 64 KB     - medium (product catalog page)
    "1048576"   # 1 MB      - large (ML embedding batch)
    "10485760"  # 10 MB     - huge (image/tensor)
)

PAYLOAD_NAMES=("64B" "1KB" "64KB" "1MB" "10MB")

echo -e "${BOLD}Cross-Runtime Transport Baseline Benchmark${NC}"
echo -e "Measuring HTTP latency by payload size to establish baseline"
echo -e "Before: HTTP | After: UDS + shared memory (kubefn-bridge)"
echo ""

# ── Generate test payloads ──
TMPDIR=$(mktemp -d)
for i in "${!PAYLOADS[@]}"; do
    size=${PAYLOADS[$i]}
    dd if=/dev/urandom of="${TMPDIR}/payload_${size}.bin" bs=1 count="${size}" 2>/dev/null
    # Also create a JSON payload (more realistic)
    python3 -c "
import json, sys
size = int(sys.argv[1])
# Create a realistic JSON object with nested data
data = {
    'type': 'PricingResult',
    'price': 84.99,
    'currency': 'USD',
    'items': [{'sku': f'SKU-{i}', 'qty': i+1, 'price': 9.99*i} for i in range(max(1, size // 100))],
    'padding': 'x' * max(0, size - 200)
}
json.dump(data, sys.stdout)
" "${size}" > "${TMPDIR}/payload_${size}.json"
done

# ── Test 1: Loopback HTTP (baseline) ──
echo -e "${BOLD}Test 1: HTTP Loopback (localhost)${NC}"
echo -e "This is what KubeFn currently uses for cross-runtime calls"
echo ""

# Start a minimal HTTP echo server
python3 -c "
from http.server import HTTPServer, BaseHTTPRequestHandler
import sys

class EchoHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length)
        self.send_response(200)
        self.send_header('Content-Type', 'application/octet-stream')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, format, *args):
        pass  # suppress logging

HTTPServer(('127.0.0.1', 19876), EchoHandler).serve_forever()
" &
HTTP_PID=$!
sleep 1

echo -e "  ${CYAN}Payload Size | Requests | Avg Latency | p99 Latency | Throughput${NC}"
echo -e "  -----------|----------|-------------|-------------|----------"

for i in "${!PAYLOADS[@]}"; do
    size=${PAYLOADS[$i]}
    name=${PAYLOAD_NAMES[$i]}

    # Use hey for benchmarking
    result=$(hey -n 5000 -c 10 -m POST \
        -D "${TMPDIR}/payload_${size}.bin" \
        http://127.0.0.1:19876/echo 2>&1)

    avg=$(echo "$result" | grep "Average:" | awk '{print $2}')
    p99=$(echo "$result" | grep "99%" | awk '{print $2}')
    rps=$(echo "$result" | grep "Requests/sec:" | awk '{print $2}')

    printf "  %-11s| %-8s | %-11s | %-11s | %s req/s\n" \
        "$name" "5000" "${avg}s" "${p99}s" "$rps"
done

kill $HTTP_PID 2>/dev/null
wait $HTTP_PID 2>/dev/null

echo ""

# ── Test 2: Unix Domain Socket (what kubefn-bridge will use) ──
echo -e "${BOLD}Test 2: Unix Domain Socket (baseline UDS echo)${NC}"
echo -e "This is the transport kubefn-bridge will use"
echo ""

UDS_SOCK="${TMPDIR}/kubefn_bench.sock"

# UDS echo server in Python
python3 -c "
import socket, os, struct, sys

sock_path = sys.argv[1]
if os.path.exists(sock_path):
    os.unlink(sock_path)

server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind(sock_path)
server.listen(32)

while True:
    conn, _ = server.accept()
    try:
        while True:
            # Read 4-byte length prefix
            hdr = conn.recv(4)
            if not hdr or len(hdr) < 4:
                break
            length = struct.unpack('!I', hdr)[0]
            # Read payload
            data = b''
            while len(data) < length:
                chunk = conn.recv(min(65536, length - len(data)))
                if not chunk:
                    break
                data += chunk
            # Echo back with length prefix
            conn.sendall(struct.pack('!I', length) + data)
    except:
        pass
    finally:
        conn.close()
" "$UDS_SOCK" &
UDS_PID=$!
sleep 1

# UDS benchmark client
python3 -c "
import socket, struct, time, sys, os

sock_path = sys.argv[1]
payloads = [64, 1024, 65536, 1048576, 10485760]
names = ['64B', '1KB', '64KB', '1MB', '10MB']
iterations = 5000

print(f'  {chr(27)}[0;36mPayload Size | Requests | Avg Latency   | p99 Latency   | Throughput{chr(27)}[0m')
print(f'  -----------|----------|---------------|---------------|----------')

for idx, size in enumerate(payloads):
    payload = os.urandom(size)
    header = struct.pack('!I', size)

    latencies = []

    # Fewer iterations for large payloads
    n = min(iterations, 500 if size > 100000 else iterations)

    conn = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    conn.connect(sock_path)

    for _ in range(n):
        start = time.perf_counter_ns()
        conn.sendall(header + payload)

        # Read response header
        hdr = conn.recv(4)
        resp_len = struct.unpack('!I', hdr)[0]
        # Read response
        data = b''
        while len(data) < resp_len:
            chunk = conn.recv(min(65536, resp_len - len(data)))
            if not chunk:
                break
            data += chunk

        elapsed_ns = time.perf_counter_ns() - start
        latencies.append(elapsed_ns)

    conn.close()

    latencies.sort()
    avg_us = sum(latencies) / len(latencies) / 1000
    p99_us = latencies[int(len(latencies) * 0.99)] / 1000
    rps = 1_000_000_000 / (sum(latencies) / len(latencies))

    print(f'  {names[idx]:<11}| {n:<8} | {avg_us:>10.1f} us  | {p99_us:>10.1f} us  | {rps:>8.0f} req/s')

" "$UDS_SOCK"

kill $UDS_PID 2>/dev/null
wait $UDS_PID 2>/dev/null

echo ""

# ── Test 3: Shared Memory (mmap baseline) ──
echo -e "${BOLD}Test 3: Shared Memory (mmap read/write baseline)${NC}"
echo -e "This is the data plane kubefn-bridge will use for large payloads"
echo ""

python3 -c "
import mmap, os, time, tempfile

payloads = [64, 1024, 65536, 1048576, 10485760]
names = ['64B', '1KB', '64KB', '1MB', '10MB']
iterations = 10000

print(f'  {chr(27)}[0;36mPayload Size | Ops      | Write Latency | Read Latency  | Combined{chr(27)}[0m')
print(f'  -----------|----------|---------------|---------------|----------')

for idx, size in enumerate(payloads):
    payload = os.urandom(size)
    n = min(iterations, 1000 if size > 100000 else iterations)

    # Create shared memory region
    fd = os.open('/dev/zero', os.O_RDWR)
    mm = mmap.mmap(fd, size + 4096, mmap.MAP_SHARED | mmap.MAP_ANONYMOUS,
                   mmap.PROT_READ | mmap.PROT_WRITE, -1, 0)
    os.close(fd)

    write_times = []
    read_times = []

    for _ in range(n):
        # Write
        start = time.perf_counter_ns()
        mm.seek(0)
        mm.write(payload)
        w_ns = time.perf_counter_ns() - start
        write_times.append(w_ns)

        # Read
        start = time.perf_counter_ns()
        mm.seek(0)
        data = mm.read(size)
        r_ns = time.perf_counter_ns() - start
        read_times.append(r_ns)

    mm.close()

    avg_w = sum(write_times) / len(write_times) / 1000
    avg_r = sum(read_times) / len(read_times) / 1000
    combined = avg_w + avg_r

    print(f'  {names[idx]:<11}| {n:<8} | {avg_w:>10.1f} us  | {avg_r:>10.1f} us  | {combined:>7.1f} us')

"

echo ""
echo -e "${BOLD}Summary${NC}"
echo "HTTP: full TCP stack + HTTP parsing + JSON serialization"
echo "UDS:  Unix socket + length-prefix framing (no HTTP overhead)"
echo "mmap: Direct memory write/read (no transport at all)"
echo ""
echo "kubefn-bridge target: UDS control + mmap data = best of both"

# Cleanup
rm -rf "$TMPDIR"
