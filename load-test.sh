#!/usr/bin/env bash
# load-test.sh — Load test the Fraud Engine transaction evaluation API
#
# Usage:
#   chmod +x load-test.sh
#   ./load-test.sh                                    # 200 requests, 20 concurrent, localhost:8080
#   TOTAL_REQUESTS=500 CONCURRENCY=50 ./load-test.sh  # bigger burst
#   BASE_URL=http://fraud.internal:8080 ./load-test.sh
#
# Environment variables (all optional):
#   TOTAL_REQUESTS  number of POSTs to send        (default: 200)
#   CONCURRENCY     max requests in flight at once (default: 20)
#   BASE_URL        service base URL               (default: http://localhost:8080)
#   API_ENDPOINT    evaluation endpoint path       (default: /api/v1/transactions/evaluate)
#   AUTH_TOKEN      JWT bearer token (required when security is enabled)
#
# Every payload carries a unique UUIDv4 id and an ISO-8601 UTC timestamp, so each POST is a
# distinct transaction (a repeated id would be rejected with 409 Conflict).
# Requires: curl, bc, and python3 (or uuidgen).

set -euo pipefail

TOTAL_REQUESTS="${TOTAL_REQUESTS:-200}"
CONCURRENCY="${CONCURRENCY:-20}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
API_ENDPOINT="${API_ENDPOINT:-/api/v1/transactions/evaluate}"
AUTH_TOKEN="${AUTH_TOKEN:-}"
FULL_URL="${BASE_URL}${API_ENDPOINT}"
HEALTH_URL="${BASE_URL}/actuator/health"
AUTH_ARGS=()
if [[ -n "$AUTH_TOKEN" ]]; then
    AUTH_ARGS=(-H "Authorization: Bearer ${AUTH_TOKEN}")
fi

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }

# Safe bc evaluation: always prints exactly one non-empty numeric token.
# `bc -l` output is truncated to its first line and stripped of whitespace, so the
# value can never leak a newline into a printf format argument.
safe_bc() {
    local out
    out=$(printf '%s\n' "$1" | bc -l 2>/dev/null | head -n 1 | tr -d '[:space:]' || true)
    printf '%s' "${out:-0}"
}

# Counts lines matching an exact HTTP status code.
# NOTE: `grep -c` already prints "0" when nothing matches (and exits 1), so it must not be
# combined with `|| echo 0` — that produced "0\n0" and blew up the summary printf.
count_status() {
    local code="$1" n
    n=$(awk -v code="$code" '$1 == code { count++ } END { print count + 0 }' "$results_file" 2>/dev/null) || n=0
    printf '%s' "${n:-0}"
}

# Latency percentiles in milliseconds, from the recorded request times.
latency_percentile() {
    local percentile="$1"
    awk -v p="$percentile" '
        $2 != "" { print $2 * 1000 }
    ' "$results_file" 2>/dev/null | sort -n | awk -v p="$percentile" '
        { values[NR] = $1 }
        END {
            if (NR == 0) { print "n/a"; exit }
            rank = int(p * NR / 100)
            if (rank < 1) rank = 1
            if (rank > NR) rank = NR
            printf "%.1f", values[rank]
        }
    '
}

new_uuid() {
    if command -v uuidgen >/dev/null 2>&1; then
        uuidgen | tr '[:upper:]' '[:lower:]'
    else
        python3 -c 'import uuid; print(uuid.uuid4())'
    fi
}

new_timestamp() {
    python3 -c 'import datetime, random
now = datetime.datetime.now(datetime.timezone.utc)
past = now - datetime.timedelta(seconds=random.randint(0, 3600))
print(past.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z")'
}

main() {
# Auto-fetch JWT token if not explicitly provided but credentials exist
    if [[ -z "${AUTH_TOKEN:-}" ]]; then
        if [[ -n "${KEYCLOAK_USERNAME:-}" && -n "${KEYCLOAK_PASSWORD:-}" ]]; then
            log_info "Fetching JWT token from Keycloak for ${KEYCLOAK_USERNAME}..."
            KC_URL="${KEYCLOAK_URL:-http://localhost:8180}"
            TOKEN_RESPONSE=$(python3 -c "
import urllib.request, urllib.parse, json
data = urllib.parse.urlencode({
    'client_id': 'fraud-engine-client',
    'username': '${KEYCLOAK_USERNAME}',
    'password': '${KEYCLOAK_PASSWORD}',
    'grant_type': 'password'
}).encode()
req = urllib.request.Request('${KC_URL}/realms/fraud-engine/protocol/openid-connect/token', data=data)
try:
    with urllib.request.urlopen(req) as response:
        print(json.loads(response.read().decode()).get('access_token', ''))
except Exception as e:
    print('')
              ")
            AUTH_TOKEN="$(echo "$TOKEN_RESPONSE" | tr -d '[:space:]')"
            if [[ -n "$AUTH_TOKEN" ]]; then
                log_success "Successfully obtained JWT bearer token"
                AUTH_ARGS=(-H "Authorization: Bearer ${AUTH_TOKEN}")
            else
                log_warn "Could not auto-fetch token from Keycloak (is Keycloak running on port 8180?)"
            fi
        fi
    fi
    if ! command -v curl >/dev/null; then log_error "curl not found"; exit 1; fi
    if ! command -v bc >/dev/null; then log_error "bc not found"; exit 1; fi
    if ! command -v python3 >/dev/null; then
        log_error "python3 required (payload timestamps and timing)"; exit 1
    fi

    # Isolate payloads/results and clean up on exit (incl. failures)
    workdir=$(mktemp -d "${TMPDIR:-/tmp}/fraud-load.XXXXXX")
    trap 'rm -rf "$workdir"' EXIT
    results_file="$workdir/results.txt"

    echo "=================================================================="
    echo "  Fraud Engine Load Test"
    echo "=================================================================="
    echo "Target URL:       ${FULL_URL}"
    echo "Total Requests:   ${TOTAL_REQUESTS}"
    echo "Concurrency:      ${CONCURRENCY}"
    echo "=================================================================="

    if ! curl -sf --max-time 10 "${HEALTH_URL}" >/dev/null; then
        log_error "Health check failed at ${HEALTH_URL} — is the service running?"
        exit 1
    fi
    log_success "Service is healthy"

    log_info "Generating ${TOTAL_REQUESTS} request payloads..."
    for ((i = 1; i <= TOTAL_REQUESTS; i++)); do
        uuid=$(new_uuid)
        timestamp=$(new_timestamp)
        if (( RANDOM % 10 < 7 )); then
            amount=$((RANDOM % 4990 + 10)).$((RANDOM % 100))
        else
            amount=$((RANDOM % 50000 + 50000)).$((RANDOM % 100))
        fi
        {
            echo '{'
            echo "  \"id\": \"${uuid}\","
            echo "  \"accountId\": \"ACC-LT-$(((i - 1) / 10 + 1))\","
            echo "  \"amount\": ${amount},"
            echo '  "currency": "ZAR",'
            echo '  "category": "TEST",'
            echo '  "merchantName": "Load Test",'
            echo '  "location": "Johannesburg",'
            echo "  \"timestamp\": \"${timestamp}\""
            echo '}'
        } > "$workdir/req_$(printf '%05d' "$i").json"
    done

    : > "$results_file"
    log_info "Starting load test (concurrency ${CONCURRENCY})..."
    start_time=$(python3 -c 'import time; print(time.time())')

    # Keep exactly CONCURRENCY requests in flight at any time (rolling pool, no wave barriers).
    # `jobs -rp` is used instead of `wait -n` so this also works on bash 3.2 (macOS default).
    for ((j = 1; j <= TOTAL_REQUESTS; j++)); do
        while :; do
            running=$(jobs -rp | wc -l | tr -d '[:space:]')
            if (( running < CONCURRENCY )); then break; fi
            sleep 0.02
        done
        curl -s -w '%{http_code} %{time_total}\n' -o /dev/null \
            --max-time 30 \
            -H 'Content-Type: application/json' \
            "${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"}" \
            -d @"$workdir/req_$(printf '%05d' "$j").json" \
            "$FULL_URL" >> "$results_file" &
    done
    wait

    end_time=$(python3 -c 'import time; print(time.time())')

    duration=$(safe_bc "$end_time - $start_time")
    total=$(wc -l < "$results_file" | tr -d '[:space:]')
    total=${total:-0}

    http_201=$(count_status 201)
    http_409=$(count_status 409)
    http_429=$(count_status 429)
    http_400=$(count_status 400)
    http_500=$(count_status 500)
    http_503=$(count_status 503)
    counted=$((http_201 + http_409 + http_429 + http_400 + http_500 + http_503))
    http_other=$((total - counted))

    throughput=0
    if (( $(safe_bc "$duration > 0") )); then
        throughput=$(safe_bc "$total / $duration")
    fi

    success_rate=0
    if (( total > 0 )); then
        success_rate=$(safe_bc "$http_201 * 100 / $total")
    fi

    echo
    echo "=================================================================="
    echo "  Load Test Results"
    echo "=================================================================="
    printf "  Duration:           %.2f seconds\n" "$duration"
    printf "  Total Requests:     %d\n" "$total"
    printf "  Throughput:         %.2f req/sec\n" "$throughput"
    printf "  Latency p50:       %s ms\n" "$(latency_percentile 50)"
    printf "  Latency p95:       %s ms\n" "$(latency_percentile 95)"
    printf "  Latency p99:       %s ms\n" "$(latency_percentile 99)"
    echo "  ----------------------------------------------------------------"
    printf "  201 Created:        %d\n" "$http_201"
    printf "  409 Conflict:       %d\n" "$http_409"
    printf "  429 Too Many Req:   %d\n" "$http_429"
    printf "  400 Bad Request:    %d\n" "$http_400"
    printf "  500 Server Error:   %d\n" "$http_500"
    printf "  503 Unavailable:    %d\n" "$http_503"
    printf "  Other:              %d\n" "$http_other"
    printf "  Success Rate:       %.1f%%\n" "$success_rate"
    echo "=================================================================="

    if (( $(safe_bc "$throughput > 100") )); then
        log_success "Excellent throughput (>100 req/sec)"
    elif (( $(safe_bc "$throughput > 50") )); then
        log_success "Good throughput (>50 req/sec)"
    elif (( $(safe_bc "$throughput > 20") )); then
        log_warn "Moderate throughput (>20 req/sec)"
    else
        log_warn "Low throughput (<20 req/sec) - consider scaling"
    fi

    if (( http_other > 0 )); then
        log_warn "${http_other} response(s) returned a status code outside the expected set"
    fi
}

main "$@"
