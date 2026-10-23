#!/usr/bin/env bash
# Regenerate docs/openapi.yaml from the running service. Springdoc publishes the
# same document as JSON at /v3/api-docs and as YAML at /v3/api-docs.yaml.
#
# Run the dev profile with a reachable database and Kafka, then:
#   BASE_URL=http://localhost:8080 ./scripts/sync_openapi.sh
#
# The Postman collection keeps curated examples and assertions, so it is checked
# against this document by scripts/check_api_contract.py instead of being
# overwritten with an empty generated collection.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

curl --fail --silent --show-error "$BASE_URL/v3/api-docs.yaml" -o "$ROOT/docs/openapi.yaml"
echo "Wrote $ROOT/docs/openapi.yaml from $BASE_URL/v3/api-docs.yaml"
