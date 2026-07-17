#!/usr/bin/env bash
# End-to-end smoke test against the docker-compose stack.
# Usage: docker compose up --build -d && ./scripts/smoke-test.sh
# Requires: curl, jq, docker compose.
#
# Covers: submit -> duplicate replay -> out-of-order listing -> balance,
# then stops the Account Service and verifies graceful degradation
# (503 on writes/balance, local reads and Gateway health still fine).
set -euo pipefail

# Match GATEWAY_PORT / ACCOUNT_SERVICE_PORT overrides given to docker compose
GATEWAY="http://localhost:${GATEWAY_PORT:-8080}"
ACCOUNT="http://localhost:${ACCOUNT_SERVICE_PORT:-8081}"
PASS=0
FAIL=0

check() { # check <description> <actual> <expected>
  local desc="$1" actual="$2" expected="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "  ok: $desc"
    PASS=$((PASS + 1))
  else
    echo "  FAIL: $desc (expected '$expected', got '$actual')"
    FAIL=$((FAIL + 1))
  fi
}

wait_for() { # wait_for <name> <url>
  local name="$1" url="$2"
  for _ in $(seq 1 30); do
    if curl -fsS "$url" > /dev/null 2>&1; then
      echo "$name is up"
      return 0
    fi
    sleep 2
  done
  echo "$name did not become healthy at $url" >&2
  exit 1
}

submit() { # submit <eventId> <type> <amount> <timestamp> -> "HTTPCODE BODY"
  curl -sS -w '\n%{http_code}' -X POST "$GATEWAY/events" \
    -H 'Content-Type: application/json' \
    -d "{
          \"eventId\": \"$1\",
          \"accountId\": \"acct-smoke\",
          \"type\": \"$2\",
          \"amount\": $3,
          \"currency\": \"USD\",
          \"eventTimestamp\": \"$4\"
        }"
}

status_of() { tail -n1 <<< "$1"; }
body_of() { sed '$d' <<< "$1"; }

echo "== Waiting for services =="
wait_for "account-service" "$ACCOUNT/health"
wait_for "event-gateway" "$GATEWAY/health"

echo "== Submit (deliberately out of chronological order) =="
r=$(submit evt-smoke-2 DEBIT 40.00 "2026-05-15T14:02:00Z")
check "second event (submitted first) -> 201" "$(status_of "$r")" "201"
r=$(submit evt-smoke-1 CREDIT 100.00 "2026-05-15T14:01:00Z")
check "first event (submitted second) -> 201" "$(status_of "$r")" "201"
r=$(submit evt-smoke-3 CREDIT 10.00 "2026-05-15T14:03:00Z")
check "third event -> 201" "$(status_of "$r")" "201"

echo "== Duplicate replay =="
r=$(submit evt-smoke-1 CREDIT 100.00 "2026-05-15T14:01:00Z")
check "duplicate eventId -> 200" "$(status_of "$r")" "200"
check "duplicate returns original event" \
  "$(body_of "$r" | jq -r '.eventId')" "evt-smoke-1"

echo "== Chronological listing =="
order=$(curl -sS "$GATEWAY/events?account=acct-smoke" | jq -r '[.events[].eventId] | join(",")')
check "listing is chronological despite arrival order" \
  "$order" "evt-smoke-1,evt-smoke-2,evt-smoke-3"

echo "== Balance through the Gateway proxy =="
# numeric comparison: the service reports the stored scale (70.0000)
balance_ok=$(curl -sS "$GATEWAY/accounts/acct-smoke/balance" | jq -r '.balance == 70')
check "balance = 100 - 40 + 10" "$balance_ok" "true"

echo "== Stopping account-service (graceful degradation) =="
docker compose stop account-service > /dev/null

r=$(submit evt-smoke-4 CREDIT 5.00 "2026-05-15T14:04:00Z")
check "write with downstream down -> 503" "$(status_of "$r")" "503"
check "503 body names the cause" \
  "$(body_of "$r" | jq -r '.message')" "Account Service is unreachable"

code=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/events/evt-smoke-1")
check "local event read still works -> 200" "$code" "200"

code=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/events?account=acct-smoke")
check "local event listing still works -> 200" "$code" "200"

code=$(curl -sS -o /dev/null -w '%{http_code}' "$GATEWAY/accounts/acct-smoke/balance")
check "balance proxy with downstream down -> 503" "$code" "503"

gw_health=$(curl -sS "$GATEWAY/health" | jq -r '.status')
check "gateway health still UP" "$gw_health" "UP"

echo "== Restarting account-service =="
docker compose start account-service > /dev/null
wait_for "account-service" "$ACCOUNT/health"

# The circuit breaker stays open for up to 10s before probing; keep retrying
# the same event until it half-opens and the write goes through.
recovered=""
for _ in $(seq 1 15); do
  r=$(submit evt-smoke-4 CREDIT 5.00 "2026-05-15T14:04:00Z")
  if [[ "$(status_of "$r")" == "201" ]]; then
    recovered="201"
    break
  fi
  sleep 2
done
check "same eventId retried after recovery -> 201 (circuit closed again)" "$recovered" "201"

echo
echo "Result: $PASS passed, $FAIL failed"
[[ "$FAIL" -eq 0 ]]
