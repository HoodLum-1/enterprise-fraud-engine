#!/usr/bin/env bash
set -e

BASE_URL="${BASE_URL:-http://localhost:8080}"
KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8180}"
KEYCLOAK_USERNAME="${KEYCLOAK_USERNAME:-INSERT_CREDS_HERE<>}"
KEYCLOAK_PASSWORD="${KEYCLOAK_PASSWORD:-INSERT_CREDS_HERE<>}"

if [ "$KEYCLOAK_USERNAME" = "INSERT_CREDS_HERE<>" ] || [ "$KEYCLOAK_PASSWORD" = "INSERT_CREDS_HERE<>" ]; then
    echo "Set KEYCLOAK_USERNAME and KEYCLOAK_PASSWORD to credentials for a configured Keycloak analyst account."
    exit 1
fi

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

echo ""
echo "------------------------------------------------------------"
echo "0. Waiting for Keycloak and obtaining Analyst Token..."
echo "------------------------------------------------------------"
TOKEN=""
for attempt in $(seq 1 20); do
    TOKEN_RESPONSE=$(curl -s -X POST "$KEYCLOAK_URL/realms/fraud-engine/protocol/openid-connect/token" \
      -H 'Content-Type: application/x-www-form-urlencoded' \
      -d 'client_id=fraud-engine-client' \
      -d 'grant_type=password' \
      --data-urlencode "username=$KEYCLOAK_USERNAME" \
      --data-urlencode "password=$KEYCLOAK_PASSWORD" 2>/dev/null || true)

    TOKEN=$(echo "$TOKEN_RESPONSE" | python3 -c 'import json, sys;
try:
    print(json.load(sys.stdin).get("access_token", ""))
except Exception:
    print("")
' 2>/dev/null)

    if [ -n "$TOKEN" ] && [ "$TOKEN" != "None" ]; then
        break
    fi
    echo "   Waiting for Keycloak to start up... (Attempt $attempt/20)"
    sleep 3
done

if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
    echo "❌ Failed to obtain authentication token from Keycloak within 60 seconds!"
    echo "Response: $TOKEN_RESPONSE"
    exit 1
fi
echo "✅ Successfully authenticated and retrieved Bearer token."

echo ""
echo "------------------------------------------------------------"
echo "Checking that application authentication is enabled..."
echo "------------------------------------------------------------"
SECURITY_CHECK_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
  "$BASE_URL/api/v1/fraud-cases")
if [ "$SECURITY_CHECK_CODE" -ne 401 ]; then
    echo "❌ Authenticated smoke tests require security.enabled=true (expected unauthenticated HTTP 401, got $SECURITY_CHECK_CODE)."
    echo "   Configure Keycloak and restart the application with SECURITY_ENABLED=true."
    exit 1
fi
echo "✅ Unauthenticated protected endpoint is denied; security is enabled."

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
  -H "Authorization: Bearer $TOKEN" \
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
  -H "Authorization: Bearer $TOKEN" \
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
  -H "Authorization: Bearer $TOKEN" \
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
  -H "Authorization: Bearer $TOKEN" \
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
HTTP_CODE=$(curl -s -o /tmp/alerts_response.json -w "%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  -X GET "$BASE_URL/api/v1/fraud-alerts/account/$ACCOUNT_ID")

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
echo "6. Waiting for fraud case to be created by Kafka consumer..."
echo "------------------------------------------------------------"
CASE_URL="$BASE_URL/api/v1/fraud-cases/transaction/$HIGH_AMOUNT_TX_ID"
HTTP_CODE=404
for attempt in $(seq 1 15); do
    HTTP_CODE=$(curl -s -o /tmp/fraud_case_response.json -w "%{http_code}" \
      -H "Authorization: Bearer $TOKEN" \
      "$CASE_URL")
    if [ "$HTTP_CODE" -eq 200 ]; then
        break
    fi
    if [ "$HTTP_CODE" -ne 404 ]; then
        echo "❌ Fraud case lookup failed! Expected 200 or 404 while waiting, got $HTTP_CODE"
        cat /tmp/fraud_case_response.json
        exit 1
    fi
    sleep 1
done

echo "Response code: $HTTP_CODE"
cat /tmp/fraud_case_response.json
echo ""

if [ "$HTTP_CODE" -ne 200 ]; then
    echo "❌ Fraud case was not created within 15 seconds."
    exit 1
fi
echo "✅ Fraud case created and retrieved successfully."

echo ""
echo "------------------------------------------------------------"
echo "7. Updating Fraud Case Status (Expect HTTP 200 OK)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/fraud_case_update_response.json -w "%{http_code}" -X PATCH \
  -H "Authorization: Bearer $TOKEN" \
  "$BASE_URL/api/v1/fraud-cases/$HIGH_AMOUNT_TX_ID/status?status=CONFIRMED_FRAUD")

echo "Response code: $HTTP_CODE"
cat /tmp/fraud_case_update_response.json
echo ""

if [ "$HTTP_CODE" -ne 200 ]; then
    echo "❌ Fraud case status update failed! Expected 200, got $HTTP_CODE"
    exit 1
fi
echo "✅ Fraud case status updated successfully."

echo ""
echo "------------------------------------------------------------"
echo "8. Checking Actuator Prometheus Metrics (Expect HTTP 200 OK)..."
echo "------------------------------------------------------------"
HTTP_CODE=$(curl -s -o /tmp/metrics_response.txt -w "%{http_code}" -H "Authorization: Bearer $TOKEN" \
  -X GET "$BASE_URL/actuator/prometheus")

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
