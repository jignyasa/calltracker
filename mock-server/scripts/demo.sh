#!/usr/bin/env bash
# Walks through the API against a running server: lookup, idempotent lead creation,
# per-item batch results, duplicate replay and conflict. Usage: scripts/demo.sh [base-url]
set -euo pipefail
BASE="${1:-http://localhost:3000}"
AUTH="Authorization: Bearer ${API_TOKEN:-dev-token}"
JSON="Content-Type: application/json"
uuid() { node -e 'console.log(crypto.randomUUID())'; }
now() { node -e 'console.log(Date.now())'; }
step() { printf '\n== %s\n' "$1"; }

step "Lookup known contact (local format)"
curl -s -H "$AUTH" "$BASE/api/v1/contacts/lookup?phone=09876543210"; echo

step "Lookup unknown number"
curl -s -H "$AUTH" "$BASE/api/v1/contacts/lookup?phone=9000012345"; echo

KEY=$(uuid)
step "Create lead (key $KEY), then replay the same request"
BODY='{"name":"Demo Lead","phone":"9000012345","company":"Demo Pvt Ltd"}'
curl -s -i -H "$AUTH" -H "$JSON" -H "Idempotency-Key: $KEY" -d "$BODY" "$BASE/api/v1/leads" | grep -iE '^HTTP|replayed|"lead"'
curl -s -i -H "$AUTH" -H "$JSON" -H "Idempotency-Key: $KEY" -d "$BODY" "$BASE/api/v1/leads" | grep -iE '^HTTP|replayed|"lead"'

T=$(now); START=$((T - 120000)); END=$((START + 60000))
GOOD=$(uuid); BAD=$(uuid)
ITEM="{\"clientId\":\"$GOOD\",\"phone\":\"+919876543210\",\"callType\":\"OUTGOING\",\"startedAt\":$START,\"endedAt\":$END,\"durationSec\":60,\"contactId\":\"con_00001\",\"notes\":\"Pricing call\",\"outcome\":\"INTERESTED\",\"updatedAt\":$T}"
BADITEM="{\"clientId\":\"$BAD\",\"phone\":\"123\",\"callType\":\"VIDEO\",\"startedAt\":$START,\"endedAt\":$END,\"durationSec\":60,\"updatedAt\":$T}"

step "Batch with one valid + one invalid item (per-item results)"
curl -s -H "$AUTH" -H "$JSON" -d "{\"items\":[$ITEM,$BADITEM]}" "$BASE/api/v1/activities/batch"; echo

step "Replay the valid item (expect DUPLICATE, same serverId)"
curl -s -H "$AUTH" -H "$JSON" -d "{\"items\":[$ITEM]}" "$BASE/api/v1/activities/batch"; echo

step "Stale edit (older updatedAt, different notes) -> CONFLICT"
STALE=${ITEM/Pricing call/Edited offline}; STALE=${STALE/\"updatedAt\":$T/\"updatedAt\":$((T - 1))}
curl -s -H "$AUTH" -H "$JSON" -d "{\"items\":[$STALE]}" "$BASE/api/v1/activities/batch"; echo

step "Injected 503 via X-Mock-Fault"
curl -s -i -H "$AUTH" -H "X-Mock-Fault: 503" "$BASE/api/v1/contacts/lookup?phone=9876543210" | grep -iE '^HTTP|retry-after'
