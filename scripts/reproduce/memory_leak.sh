#!/bin/bash
# =============================================================
# memory_leak.sh — SSE emitter 반복 구독/해제로 메모리 누수 확인
#
# 시나리오:
#   1. 여러 conferenceId로 구독 후 정상 종료(unsubscribe)
#   2. lastKnownCounts Map에 키가 남는지 확인
#   3. JVM Heap 증가량으로 메모리 누수 관찰
#
# 사용법: bash scripts/reproduce/memory_leak.sh [iteration]
# 예시:   bash scripts/reproduce/memory_leak.sh 50
# =============================================================
HOST="http://localhost:8080"
ITER="${1:-30}"

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
RST='\033[0m'

get_heap_mb() {
  VAL=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" \
    | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')
  echo "scale=1; ${VAL:-0} / 1048576" | bc 2>/dev/null || echo "?"
}

get_emitter_count() {
  curl -s "${HOST}/api/v1/sse/status" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*'
}

get_last_known_size() {
  curl -s "${HOST}/api/v1/sse/status" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*'
}

echo "========================================================"
echo " [Bug-2] lastKnownCounts 메모리 누수 재연 (${ITER}회 반복)"
echo "========================================================"

echo ""
echo -e "${YLW}[초기 상태]${RST}"
echo "  emitterCount      : $(get_emitter_count)"
echo "  lastKnownCounts   : $(get_last_known_size)"
echo "  JVM Heap          : $(get_heap_mb) MB"
HEAP_BEFORE=$(get_heap_mb)

echo ""
echo -e "${YLW}[구독 → 해제 ${ITER}회 반복 중...]${RST}"

for i in $(seq 1 "$ITER"); do
  # 구독
  CURL_PID=""
  curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${i}" \
    -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
  CURL_PID=$!
  sleep 0.2

  # 정상 unsubscribe (또는 kill로 변경해서 비정상 종료 비교 가능)
  curl -s -X DELETE "${HOST}/api/v1/sse/unsubscribe?conferenceId=${i}" > /dev/null
  kill "$CURL_PID" 2>/dev/null
  sleep 0.1

  if [ $((i % 10)) -eq 0 ]; then
    echo "  [${i}/${ITER}] lastKnownCounts=$(get_last_known_size), emitter=$(get_emitter_count), heap=$(get_heap_mb)MB"
  fi
done

echo ""
echo -e "${YLW}[최종 상태]${RST}"
EMITTER_FINAL=$(get_emitter_count)
LAST_KNOWN_FINAL=$(get_last_known_size)
HEAP_AFTER=$(get_heap_mb)

echo "  emitterCount      : ${EMITTER_FINAL}  (기대: 0)"
echo -e "  lastKnownCounts   : ${RED}${LAST_KNOWN_FINAL}${RST}  (기대: 0, 버그 시: ${ITER})"
echo "  JVM Heap Before   : ${HEAP_BEFORE} MB"
echo "  JVM Heap After    : ${HEAP_AFTER} MB"

echo ""
echo "========================================================"
if [ "${LAST_KNOWN_FINAL}" -eq 0 ] 2>/dev/null; then
  echo -e "  ${GRN}[PASS] lastKnownCounts 정상 정리됨${RST}"
else
  echo -e "  ${RED}[BUG 확인] lastKnownCounts에 ${LAST_KNOWN_FINAL}개 키 잔류 — 메모리 누수!${RST}"
fi
echo "========================================================"
