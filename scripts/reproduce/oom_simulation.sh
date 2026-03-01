#!/bin/bash
# =============================================================
# oom_simulation.sh — 대량 SSE 연결 후 SIGKILL로 OOM 경향 관찰
#
# 시나리오:
#   실제 OOM은 수천 개 연결이 필요하지만,
#   여기서는 소규모(기본 100개)로 누수 경향을 수치로 확인한다.
#
#   1. N개 SSE 연결 생성 (각각 다른 conferenceId)
#   2. 모두 SIGKILL (좀비 상태)
#   3. emitter 수, lastKnownCounts, Heap 변화 관찰
#   4. 다시 N개 연결 → 누적 증가 확인
#
# 사용법: bash scripts/reproduce/oom_simulation.sh [connections_per_round] [rounds]
# 예시:   bash scripts/reproduce/oom_simulation.sh 20 3
# =============================================================
HOST="http://localhost:8080"
CONN="${1:-20}"
ROUNDS="${2:-3}"

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

get_status() {
  STATUS=$(curl -s "${HOST}/api/v1/sse/status")
  EMITTER=$(echo "$STATUS" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
  LAST=$(echo "$STATUS"   | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')
  HEAP=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" \
    | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')
  HEAP_MB=$(echo "scale=1; ${HEAP:-0} / 1048576" | bc 2>/dev/null || echo "?")
  echo "emitter=${EMITTER:-?} | lastKnown=${LAST:-?} | heap=${HEAP_MB}MB"
}

echo "========================================================"
echo " [BUG-1/2/6] OOM 경향 시뮬레이션"
echo " ${ROUNDS}라운드 × ${CONN}연결 → SIGKILL → 누적 잔류 확인"
echo "========================================================"

echo ""
echo -e "${CYN}[초기] $(get_status)${RST}"

ALL_PIDS=()
BASE_CONF=0

for round in $(seq 1 "$ROUNDS"); do
  echo ""
  echo -e "${YLW}━━━ Round ${round}/${ROUNDS} : ${CONN}개 연결 생성 ━━━${RST}"

  ROUND_PIDS=()
  for i in $(seq 1 "$CONN"); do
    CONF_ID=$((BASE_CONF + i))
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF_ID}" \
      -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    ROUND_PIDS+=($!)
    ALL_PIDS+=($!)
  done
  BASE_CONF=$((BASE_CONF + CONN))

  sleep 1
  echo -e "  연결 후  : $(get_status)"

  # SIGKILL — 좀비 소켓
  for pid in "${ROUND_PIDS[@]}"; do
    kill -9 "$pid" 2>/dev/null
  done

  echo "  SIGKILL 후 5초 대기..."
  sleep 5
  echo -e "  정리 후  : ${RED}$(get_status)${RST}"
done

echo ""
echo "========================================================"
echo -e " ${RED}최종 결과 (${ROUNDS}라운드 × ${CONN}연결 = 총 $((ROUNDS * CONN))개 생성 후):${RST}"
FINAL=$(get_status)
echo -e "  ${RED}${FINAL}${RST}"
echo ""
echo " 수정 전 기대: emitter=${ROUNDS}*${CONN}=$(( ROUNDS * CONN )) 잔류 (OOM 경향)"
echo " 수정 후 기대: emitter=0 (heartbeat 또는 CLOSE_WAIT 감지로 정리됨)"
echo "========================================================"

# 정리
for pid in "${ALL_PIDS[@]}"; do kill "$pid" 2>/dev/null; done
