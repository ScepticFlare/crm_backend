#!/bin/bash
set -u

HOST="aws-0-ap-southeast-1.pooler.supabase.com"
PORT="5432"

echo "=== DB CONNECTIVITY DIAGNOSTIC START ==="

# 1. DNS
echo "--- [1/4] DNS resolution: $HOST ---"
if RESOLVED=$(getent hosts "$HOST" 2>&1); then
  echo "DNS: OK"
  echo "$RESOLVED"
else
  echo "DNS: FAIL"
  echo "$RESOLVED"
  echo "=== RESULT: FAILED AT DNS LAYER ==="
  exec java -jar app.jar
fi

# 2. TCP
echo "--- [2/4] TCP connectivity to $HOST:$PORT ---"
if timeout 10 bash -c "exec 3<>/dev/tcp/$HOST/$PORT" 2>/tmp/tcp_err; then
  echo "TCP: OK (connected)"
else
  echo "TCP: FAIL"
  cat /tmp/tcp_err
  echo "=== RESULT: FAILED AT TCP LAYER ==="
  exec java -jar app.jar
fi

# 3. TLS handshake (raw Postgres SSLRequest + TLS upgrade - same sequence pgjdbc performs)
echo "--- [3/4] TLS handshake (STARTTLS postgres) to $HOST:$PORT ---"
TLS_OUT=$(echo | timeout 10 openssl s_client -connect "$HOST:$PORT" -starttls postgres 2>&1)
if echo "$TLS_OUT" | grep -q "Verify return code"; then
  echo "TLS: OK (handshake completed)"
  echo "$TLS_OUT" | grep -E "subject=|issuer=|Verify return code|Protocol|Cipher"
else
  echo "TLS: FAIL"
  echo "$TLS_OUT"
  echo "=== RESULT: FAILED AT TLS LAYER ==="
  exec java -jar app.jar
fi

# 4. PostgreSQL authentication (reuses existing Render env vars; password never printed)
echo "--- [4/4] PostgreSQL authentication ---"
if PGPASSWORD="${SPRING_DATASOURCE_PASSWORD:-}" PGSSLMODE=require \
   psql -h "$HOST" -p "$PORT" -U "${SPRING_DATASOURCE_USERNAME:-}" -d "${SPRING_DATASOURCE_DATABASE:-postgres}" -c "SELECT 1;" \
   >/tmp/psql_out 2>&1; then
  echo "AUTH: OK"
  echo "=== RESULT: SUCCESSFUL CONNECTION ==="
else
  echo "AUTH: FAIL"
  grep -vi "password" /tmp/psql_out
  echo "=== RESULT: FAILED AT POSTGRESQL AUTH LAYER ==="
fi

echo "=== DB CONNECTIVITY DIAGNOSTIC END ==="

exec java -jar app.jar
