#!/usr/bin/env bash
set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "============================================================"
echo " Starting Fraud Engine Smoke Tests against: $BASE_URL"
echo "============================================================"

# Utility function to generate UUID (cross-platform compatible)
generate_uuid() {
    if command -v uuidgen &>/dev/null; then
        uuidgen | tr '[:upper:]' '[:lower:]'
    elif [ -f /proc/sys/kernel/random/uuid ]; then
        cat /proc/sys/kernel/random/uuid
    else
        python3 -c 'import uuid; print(str(uuid.uuid4()))' 2>/dev/null || od -x /dev/urandom | head -1 | awk '{print $2$3"-"$4"-"$5"-"$6"-"$7$8$9}'
    fi
}

ACCOUNT_ID="acc-smoke-$(date +%s)"
CLEAN_TX_ID=$(generate_uuid)
HIGH_AMOUNT_TX_ID=$(generate_uuid)
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

echo ""
echo "------------------------------------------------------------"
echo "1. Testing Clean Low-Risk Transaction (Expect HTTP 201)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/clean_tx_response.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/transactions/evaluate" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"$CLEAN_TX_ID\",
    \"accountId\": \"$ACCOUNT_ID\",
    \"amount\": 150.00,
    \"currency\": \"ZAR\",
    \"category\": \"GROCERIES\",
    \"merchantName\": \"Supermarket\",
    \"location\": \"Cape Town\",
    \"timestamp\": \"$TIMESTAMP\"
  }")

echo "Response code: $HTTP_CODE"
cat /tmp/clean_tx_response.json
echo ""

if [ "$HTTP_CODE" -ne 201 ]; then
    echo "❌ Clean transaction test failed! Expected 201, got $HTTP_CODE"
    exit 1
fi
echo "✅ Clean transaction evaluated successfully."

echo ""
echo "------------------------------------------------------------"
echo "2. Testing High-Amount Rule Trigger (Expect HTTP 201)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/high_amount_response.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/transactions/evaluate" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"$HIGH_AMOUNT_TX_ID\",
    \"accountId\": \"$ACCOUNT_ID\",
    \"amount\": 75000.00,
    \"currency\": \"ZAR\",
    \"category\": \"ELECTRONICS\",
    \"merchantName\": \"Luxury Store\",
    \"location\": \"Cape Town\",
    \"timestamp\": \"$TIMESTAMP\"
  }")

echo "Response code: $HTTP_CODE"
cat /tmp/high_amount_response.json
echo ""

if [ "$HTTP_CODE" -ne 201 ]; then
    echo "❌ High amount transaction test failed! Expected 201, got $HTTP_CODE"
    exit 1
fi
echo "✅ High amount transaction evaluated and flagged successfully."

echo ""
echo "------------------------------------------------------------"
echo "3. Testing Idempotency Duplicate Check (Expect HTTP 409 Conflict)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/duplicate_response.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/transactions/evaluate" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": \"$CLEAN_TX_ID\",
    \"accountId\": \"$ACCOUNT_ID\",
    \"amount\": 150.00,
    \"currency\": \"ZAR\",
    \"category\": \"GROCERIES\",
    \"merchantName\": \"Supermarket\",
    \"location\": \"Cape Town\",
    \"timestamp\": \"$TIMESTAMP\"
  }")

echo "Response code: $HTTP_CODE"
cat /tmp/duplicate_response.json
echo ""

if [ "$HTTP_CODE" -ne 409 ]; then
    echo "❌ Duplicate transaction check failed! Expected 409, got $HTTP_CODE"
    exit 1
fi
echo "✅ Idempotency duplicate check passed (409 Conflict)."

echo ""
echo "------------------------------------------------------------"
echo "4. Testing Bean Validation Failure (Expect HTTP 400 Bad Request)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/validation_response.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/transactions/evaluate" \
  -H "Content-Type: application/json" \
  -d "{
    \"id\": null,
    \"accountId\": \"\",
    \"amount\": -50.00,
    \"currency\": \"INVALID_CURRENCY\",
    \"category\": \"\",
    \"timestamp\": null
  }")

echo "Response code: $HTTP_CODE"
cat /tmp/validation_response.json
echo ""

if [ "$HTTP_CODE" -ne 400 ]; then
    echo "❌ Validation failure test failed! Expected 400, got $HTTP_CODE"
    exit 1
fi
echo "✅ Validation test passed (400 Bad Request)."

echo ""
echo "------------------------------------------------------------"
echo "5. Fetching Account Fraud Alerts (Expect HTTP 200 OK)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/alerts_response.json -w "%{http_code}" -X GET "$BASE_URL/api/v1/fraud-alerts/account/$ACCOUNT_ID")

echo "Response code: $HTTP_CODE"
cat /tmp/alerts_response.json
echo ""

if [ "$HTTP_CODE" -ne 200 ]; then
    echo "❌ Fetch alerts test failed! Expected 200, got $HTTP_CODE"
    exit 1
fi
echo "✅ Account fraud alerts retrieved successfully."

echo ""
echo "------------------------------------------------------------"
echo "6. Checking Actuator Prometheus Metrics (Expect HTTP 200 OK)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/metrics_response.txt -w "%{http_code}" -X GET "$BASE_URL/actuator/prometheus")

echo "Response code: $HTTP_CODE"
head -n 15 /tmp/metrics_response.txt
echo "..."
echo ""

if [ "$HTTP_CODE" -ne 200 ]; then
    echo "❌ Prometheus metrics test failed! Expected 200, got $HTTP_CODE"
    exit 1
fi
echo "✅ Actuator Prometheus metrics endpoint checked successfully."

echo ""
echo "============================================================"
echo " All Smoke Tests Passed Successfully! "
echo "============================================================"
