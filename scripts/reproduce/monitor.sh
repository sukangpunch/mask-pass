#!/bin/bash
# =============================================================
# monitor.sh — SSE 상태 + JVM 메모리 + Redis 실시간 폴링
# 사용법: bash scripts/reproduce/monitor.sh [interval_sec]
# 예시:   bash scripts/reproduce/monitor.sh 3
# =============================================================
HOST="http://localhost:8080"
INTERVAL="${1:-3}"

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
RST='\033[0m'

echo "========================================================"
echo " Mask-Pass 실시간 모니터 (${INTERVAL}초 간격)"
echo " 종료: Ctrl+C"
echo "========================================================"

while true; do
  NOW=$(date '+%H:%M:%S')

  # ── 1. SSE 상태 ──────────────────────────────────────────
  SSE_JSON=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
  EMITTER_COUNT=$(echo "$SSE_JSON" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
  LAST_KNOWN_SIZE=$(echo "$SSE_JSON" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')

  # ── 2. JVM Heap (Actuator) ───────────────────────────────
  # used / committed / max (bytes)
  HEAP_JSON=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null)
  HEAP_USED=$(echo "$HEAP_JSON" | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')
  HEAP_MB=$(echo "scale=1; ${HEAP_USED:-0} / 1048576" | bc 2>/dev/null || echo "?")

  # ── 3. Micrometer SSE 게이지 (Prometheus 포맷 직접 확인) ─
  SSE_GAUGE=$(curl -s "${HOST}/actuator/metrics/sse.emitter.count" 2>/dev/null \
    | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')

  # ── 4. Redis 키 수 ───────────────────────────────────────
  REDIS_KEYS=$(redis-cli DBSIZE 2>/dev/null || echo "N/A (redis-cli 없음)")

  # ── 출력 ─────────────────────────────────────────────────
  echo ""
  echo -e "${YLW}[${NOW}]${RST}"
  echo -e "  SSE Emitter 수        : ${RED}${EMITTER_COUNT:-?}${RST}  (Micrometer: ${SSE_GAUGE:-?})"
  echo -e "  lastKnownCounts 크기  : ${RED}${LAST_KNOWN_SIZE:-?}${RST}  ← emitter 종료 후에도 남으면 메모리 누수"
  echo -e "  JVM Heap Used         : ${GRN}${HEAP_MB} MB${RST}"
  echo -e "  Redis DBSIZE          : ${REDIS_KEYS} keys"
  echo    "  ──────────────────────────────────────────────"

  sleep "$INTERVAL"
done
