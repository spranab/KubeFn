#!/usr/bin/env bash
# KubeFn Honest Benchmark — Full HTTP Request Cycle
#
# Measures the COMPLETE request path:
#   HTTP request in → routing → function execution → HeapExchange ops → HTTP response out
#
# Uses 'hey' for proper HTTP benchmarking (not curl timing which includes process spawn).
# All numbers are end-to-end latency including network stack, HTTP parsing, and serialization.
#
# What we're measuring vs microservices:
#   Microservices: 1 HTTP call per service (N sequential network hops + JSON serialize/deserialize each hop)
#   KubeFn: 1 HTTP call total (functions compose in-memory, zero serialization between them)
#
# The real speedup comes from eliminating N-1 HTTP round trips and N serialization cycles.

set -euo pipefail

BOLD='\033[1m'
CYAN='\033[0;36m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

JVM_PORT="${1:-8080}"
PY_PORT="${2:-8090}"
NODE_PORT="${3:-8070}"
REQUESTS=1000
CONCURRENCY=10

echo -e "${CYAN}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║${BOLD}  KubeFn Honest Benchmark — Full HTTP Cycle              ${NC}${CYAN}║${NC}"
echo -e "${CYAN}║${NC}  Measuring: HTTP in → execution → heap ops → HTTP out   ${CYAN}║${NC}"
echo -e "${CYAN}║${NC}  Tool: hey (${REQUESTS} requests, ${CONCURRENCY} concurrent)             ${CYAN}║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════╝${NC}"
echo ""

run_benchmark() {
    local name="$1"
    local url="$2"
    local description="$3"

    echo -e "${BOLD}${name}${NC} — ${description}"

    # Warmup
    hey -n 100 -c 5 -q 100 "$url" > /dev/null 2>&1

    # Benchmark
    local result
    result=$(hey -n $REQUESTS -c $CONCURRENCY "$url" 2>&1)

    # Extract key metrics
    local avg=$(echo "$result" | grep "Average:" | awk '{print $2}')
    local p50=$(echo "$result" | grep "50%" | awk '{print $2}')
    local p95=$(echo "$result" | grep "95%" | awk '{print $2}')
    local p99=$(echo "$result" | grep "99%" | awk '{print $2}')
    local rps=$(echo "$result" | grep "Requests/sec:" | awk '{print $2}')
    local fastest=$(echo "$result" | grep "Fastest:" | awk '{print $2}')
    local slowest=$(echo "$result" | grep "Slowest:" | awk '{print $2}')

    # Convert to ms (hey reports in seconds)
    echo -e "  Requests/sec: ${GREEN}${rps}${NC}"
    echo -e "  Avg:  $(echo "$avg" | awk '{printf "%.3f", $1*1000}')ms"
    echo -e "  p50:  $(echo "$p50" | awk '{printf "%.3f", $1*1000}')ms"
    echo -e "  p95:  $(echo "$p95" | awk '{printf "%.3f", $1*1000}')ms"
    echo -e "  p99:  $(echo "$p99" | awk '{printf "%.3f", $1*1000}')ms"
    echo -e "  Min:  $(echo "$fastest" | awk '{printf "%.3f", $1*1000}')ms"
    echo -e "  Max:  $(echo "$slowest" | awk '{printf "%.3f", $1*1000}')ms"
    echo ""
}

# Check which runtimes are available
echo -e "${BOLD}Checking runtimes...${NC}"

JVM_UP=false
PY_UP=false
NODE_UP=false

curl -sf "http://localhost:${JVM_PORT}/healthz" > /dev/null 2>&1 && JVM_UP=true && echo -e "  ${GREEN}JVM:    port ${JVM_PORT}${NC}" || echo "  JVM:    not running"
curl -sf "http://localhost:${PY_PORT}/healthz" > /dev/null 2>&1 && PY_UP=true && echo -e "  ${GREEN}Python: port ${PY_PORT}${NC}" || echo "  Python: not running"
curl -sf "http://localhost:${NODE_PORT}/healthz" > /dev/null 2>&1 && NODE_UP=true && echo -e "  ${GREEN}Node:   port ${NODE_PORT}${NC}" || echo "  Node:   not running"
echo ""

# Run benchmarks for available runtimes
if [ "$JVM_UP" = true ]; then
    run_benchmark "JVM Runtime" \
        "http://localhost:${JVM_PORT}/checkout/quote?userId=bench-user" \
        "7-function checkout pipeline (auth→inventory→pricing→shipping→tax→fraud→assemble)"
fi

if [ "$PY_UP" = true ]; then
    run_benchmark "Python Runtime" \
        "http://localhost:${PY_PORT}/ml/pipeline?userId=bench-user" \
        "3-function ML pipeline (features→inference→explanation)"
fi

if [ "$NODE_UP" = true ]; then
    run_benchmark "Node.js Runtime" \
        "http://localhost:${NODE_PORT}/gw/proxy?userId=bench-user" \
        "3-function API gateway (rateLimit→auth→route)"
fi

echo -e "${CYAN}╔════════════════════════════════════════════════════════╗${NC}"
echo -e "${CYAN}║${BOLD}  How to interpret these numbers:                        ${NC}${CYAN}║${NC}"
echo -e "${CYAN}║${NC}                                                          ${CYAN}║${NC}"
echo -e "${CYAN}║${NC}  In microservices, a 7-step pipeline = 7 HTTP calls.     ${CYAN}║${NC}"
echo -e "${CYAN}║${NC}  Each hop adds ~2-10ms (network + JSON ser/deser).       ${CYAN}║${NC}"
echo -e "${CYAN}║${NC}  Total: 15-70ms just in inter-service overhead.          ${CYAN}║${NC}"
echo -e "${CYAN}║${NC}                                                          ${CYAN}║${NC}"
echo -e "${CYAN}║${NC}  In KubeFn: 1 HTTP call. Functions compose in-memory.    ${CYAN}║${NC}"
echo -e "${CYAN}║${NC}  The latency you see IS the full pipeline — not 1 hop.   ${CYAN}║${NC}"
echo -e "${CYAN}╚════════════════════════════════════════════════════════╝${NC}"
