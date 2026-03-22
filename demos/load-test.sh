#!/usr/bin/env bash
# KubeFn Load Test Harness
# Tests throughput, latency percentiles, and hot-swap stability under load
#
# Usage: ./demos/load-test.sh [host] [concurrency] [duration_seconds]

set -euo pipefail

HOST="${1:-localhost:8080}"
CONCURRENCY="${2:-50}"
DURATION="${3:-10}"
ADMIN="${HOST%:*}:8081"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m'

echo -e "${CYAN}╔═══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║${BOLD}  KubeFn Load Test Harness                      ${NC}${CYAN}║${NC}"
echo -e "${CYAN}╠═══════════════════════════════════════════════╣${NC}"
echo -e "${CYAN}║${NC}  Host:        ${BOLD}${HOST}${NC}"
echo -e "${CYAN}║${NC}  Concurrency: ${BOLD}${CONCURRENCY}${NC}"
echo -e "${CYAN}║${NC}  Duration:    ${BOLD}${DURATION}s${NC}"
echo -e "${CYAN}╚═══════════════════════════════════════════════╝${NC}"
echo ""

# Check if runtime is accessible
if ! curl -sf "http://${ADMIN}/healthz" > /dev/null 2>&1; then
    echo -e "${RED}Error: KubeFn runtime not reachable at http://${ADMIN}${NC}"
    exit 1
fi

# Get baseline metrics
echo -e "${BOLD}Phase 1: Warmup (100 requests)${NC}"
for i in $(seq 1 100); do
    curl -sf "http://${HOST}/checkout/quote?userId=user-$((i % 10))" > /dev/null &
done
wait
echo -e "  ${GREEN}Warmup complete.${NC}"
echo ""

# Reset timing arrays
RESULTS_FILE=$(mktemp)
ERRORS=0
TOTAL=0

echo -e "${BOLD}Phase 2: Load Test (${CONCURRENCY} concurrent, ${DURATION}s)${NC}"
echo "  Running..."

START_TIME=$(python3 -c "import time; print(time.time())")

# Run concurrent requests for the specified duration
END_EPOCH=$(python3 -c "import time; print(time.time() + ${DURATION})")

for worker in $(seq 1 $CONCURRENCY); do
    (
        while true; do
            NOW=$(python3 -c "import time; print(time.time())")
            if python3 -c "exit(0 if $NOW < $END_EPOCH else 1)" 2>/dev/null; then
                START=$(python3 -c "import time; print(int(time.time_ns()))")
                HTTP_CODE=$(curl -sf -o /dev/null -w "%{http_code}" \
                    "http://${HOST}/checkout/quote?userId=user-$((RANDOM % 100))" 2>/dev/null || echo "000")
                END=$(python3 -c "import time; print(int(time.time_ns()))")
                DURATION_NS=$((END - START))
                echo "${HTTP_CODE} ${DURATION_NS}" >> "${RESULTS_FILE}"
            else
                break
            fi
        done
    ) &
done
wait

ACTUAL_DURATION=$(python3 -c "import time; print(f'{time.time() - ${START_TIME}:.1f}')")

echo -e "  ${GREEN}Load test complete (${ACTUAL_DURATION}s actual).${NC}"
echo ""

# Analyze results
echo -e "${BOLD}Phase 3: Results${NC}"
echo ""

python3 << PYTHON
import sys

latencies = []
errors = 0
total = 0

with open("${RESULTS_FILE}") as f:
    for line in f:
        parts = line.strip().split()
        if len(parts) == 2:
            code, ns = parts
            total += 1
            if code == "200":
                latencies.append(int(ns) / 1_000_000)  # Convert to ms
            else:
                errors += 1

if not latencies:
    print("  No successful requests recorded.")
    sys.exit(1)

latencies.sort()
count = len(latencies)
avg = sum(latencies) / count
p50 = latencies[int(count * 0.50)]
p95 = latencies[int(count * 0.95)]
p99 = latencies[int(count * 0.99)]
p999 = latencies[min(int(count * 0.999), count - 1)]
min_lat = latencies[0]
max_lat = latencies[-1]
throughput = total / float("${ACTUAL_DURATION}")

print(f"  \033[1mThroughput:\033[0m  {throughput:.0f} req/sec")
print(f"  \033[1mTotal:\033[0m       {total} requests")
print(f"  \033[1mSuccessful:\033[0m  {count}")
print(f"  \033[1mErrors:\033[0m      {errors}")
print()
print(f"  \033[1mLatency Distribution:\033[0m")
print(f"    Min:    {min_lat:.2f}ms")
print(f"    Avg:    {avg:.2f}ms")
print(f"    p50:    {p50:.2f}ms")
print(f"    p95:    {p95:.2f}ms")
print(f"    p99:    {p99:.2f}ms")
print(f"    p99.9:  {p999:.2f}ms")
print(f"    Max:    {max_lat:.2f}ms")
print()

# Quality assessment
if p99 < 5:
    quality = "\033[32mEXCELLENT\033[0m"
elif p99 < 20:
    quality = "\033[33mGOOD\033[0m"
elif p99 < 100:
    quality = "\033[33mACCEPTABLE\033[0m"
else:
    quality = "\033[31mNEEDS IMPROVEMENT\033[0m"

print(f"  \033[1mQuality:\033[0m     {quality} (p99={p99:.2f}ms)")
PYTHON

rm -f "${RESULTS_FILE}"

echo ""

# Get metrics from runtime
echo -e "${BOLD}Phase 4: Runtime Metrics After Load${NC}"
curl -sf "http://${ADMIN}/admin/metrics" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'  Total requests:  {d[\"totalRequests\"]}')
print(f'  Total errors:    {d[\"totalErrors\"]}')
print(f'  Total timeouts:  {d[\"totalTimeouts\"]}')
print(f'  Heap publishes:  {d[\"heapPublishes\"]}')
print(f'  Heap hit rate:   {d[\"heapHits\"]}/{d[\"heapGets\"]} ({d[\"heapHits\"]/max(d[\"heapGets\"],1)*100:.1f}%)')
if d.get('functions'):
    print()
    print('  Per-function metrics:')
    for f in d['functions']:
        print(f'    {f[\"function\"]}: {f[\"count\"]} calls, avg={f[\"meanMs\"]}ms, p99={f[\"p99Ms\"]}ms')
" 2>/dev/null

echo ""
echo -e "${BOLD}Phase 5: Heap Status${NC}"
curl -sf "http://${ADMIN}/admin/heap" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
print(f'  Objects:    {d[\"objectCount\"]}')
print(f'  Hit rate:   {d[\"hitRate\"]}')
" 2>/dev/null

echo ""
echo -e "${BOLD}Phase 6: Circuit Breakers${NC}"
curl -sf "http://${ADMIN}/admin/breakers" 2>/dev/null | python3 -c "
import json, sys
d = json.load(sys.stdin)
for k, v in d.items():
    state_color = '\033[32m' if v['state'] == 'CLOSED' else '\033[31m'
    print(f'  {k}: {state_color}{v[\"state\"]}\033[0m ({v[\"successfulCalls\"]} ok, {v[\"failedCalls\"]} fail)')
" 2>/dev/null

echo ""
echo -e "${CYAN}╔═══════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║${BOLD}  Load test complete.                           ${NC}${CYAN}║${NC}"
echo -e "${CYAN}╚═══════════════════════════════════════════════╝${NC}"
