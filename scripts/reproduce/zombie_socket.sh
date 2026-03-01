#!/bin/bash
# ============================================================
# zombie_socket.sh — 좀비 소켓(CLOSE_WAIT) 재연
#
# 시나리오:
#   conference:1 + session:1,2,3 — 총 4개 SSE 동시 구독
#   → SIGKILL로 강제 종료 (FIN 없이 = 브라우저 탭 강제 닫기)
#   → 서버가 연결 끊김을 감지하지 못해 emitter 4개 잔류 확인
#
# 사용법: bash scripts/reproduce/zombie_socket.sh [wait_sec]
# 예시:   bash scripts/reproduce/zombie_socket.sh 7
#
# 사전 조건:
#   1. bash scripts/reproduce/data/setup.sh    (DB 데이터)
#   2. 서버 기동 + bash scripts/reproduce/monitor.sh (별도 터미널)
# ============================================================
HOST="http://localhost:8080"
WAIT="${1:-7}"   # SIGKILL 후 zombie 관찰 대기 시간 (초)
CONF=1
SESSION_IDS=(1 2 3)
TOTAL=4  # conference 1개 + session 3개

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

PIDS=()

# ── 헬퍼 함수 ────────────────────────────────────────────────
get_status() {
    STATUS=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
    EMITTER=$(echo "$STATUS" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
    LAST=$(echo "$STATUS"   | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')
    echo "emitter=${EMITTER:-?}개 | lastKnownCounts=${LAST:-?}개"
}

get_heap_mb() {
    HEAP=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null \
        | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')
    echo "scale=1; ${HEAP:-0} / 1048576" | bc 2>/dev/null || echo "?"
}

connect_all() {
    PIDS=()
    # 1. conference 레벨 (sessionId 없음)
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    PIDS+=($!)
    echo "  [연결] conference:${CONF}             PID=${PIDS[-1]}"

    # 2. 세션 레벨 3개
    for sid in "${SESSION_IDS[@]}"; do
        curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
            -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
        PIDS+=($!)
        echo "  [연결] conference:${CONF}:session:${sid}  PID=${PIDS[-1]}"
    done
}

kill_all() {
    for pid in "${PIDS[@]}"; do
        kill -9 "$pid" 2>/dev/null
    done
}

# ── 메인 ──────────────────────────────────────────────────────
echo "========================================================"
echo " [Bug] 좀비 소켓 재연"
echo " 구독 대상: conference:${CONF} + session:1,2,3 (총 ${TOTAL}개)"
echo " SIGKILL 후 ${WAIT}초간 zombie 상태 관찰"
echo "========================================================"

echo ""
echo -e "${YLW}[STEP 1] 연결 전 상태${RST}"
echo "  $(get_status) | heap=$(get_heap_mb)MB"

echo ""
echo -e "${YLW}[STEP 2] ${TOTAL}개 SSE 연결 시작${RST}"
connect_all
sleep 1

echo ""
echo -e "${YLW}[STEP 2] 연결 후 상태 (기대: emitter=4)${RST}"
echo "  $(get_status)"

echo ""
echo -e "${YLW}[STEP 3] SIGKILL — 4개 연결 강제 종료 (브라우저 탭 강제 닫기 시뮬레이션)${RST}"
kill_all
echo "  → 모든 curl PID에 kill -9 완료"

echo ""
echo -e "${YLW}[STEP 4] SIGKILL 직후 상태 (기대: emitter=4 zombie!)${RST}"
echo "  $(get_status)"
echo -e "  ${RED}↑ emitter가 ${TOTAL}로 남아있으면 좀비 소켓 발생${RST}"

echo ""
echo "  ${WAIT}초간 관찰 중 (서버가 연결 끊김을 감지하는지 확인)..."
for i in $(seq 1 "$WAIT"); do
    sleep 1
    COUNT=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
    printf "  [%ds 후] emitter=%s\n" "$i" "${COUNT:-?}"
done

echo ""
echo -e "${YLW}[STEP 5] 최종 상태${RST}"
AFTER=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
echo "$AFTER" | python3 -m json.tool 2>/dev/null || echo "$AFTER"

EMITTER_COUNT=$(echo "$AFTER" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
LAST_KNOWN=$(echo "$AFTER"   | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')

echo ""
echo "========================================================"
echo " 결과 요약"
echo -e "  emitterCount       : ${RED}${EMITTER_COUNT:-?}${RST}  (기대값: 0, 버그 시: ${TOTAL})"
echo -e "  lastKnownCounts    : ${RED}${LAST_KNOWN:-?}${RST}  (기대값: 0, 버그 시: ${TOTAL})"
echo ""
if [ "${EMITTER_COUNT}" -eq 0 ] 2>/dev/null; then
    echo -e "  ${GRN}[PASS] emitter 정상 정리됨 (onError/onCompletion 발화)${RST}"
else
    echo -e "  ${RED}[BUG] emitter ${EMITTER_COUNT}개 잔류 — 좀비 소켓 발생!${RST}"
    echo -e "  ${RED}      해결책: heartbeat으로 능동 감지 또는 재연결 시 cleanup${RST}"
fi
echo "========================================================"
echo ""
echo " → 다음 단계: bash scripts/reproduce/refresh_simulation.sh"
echo "   새로고침(재연결) 시 zombie가 어떻게 처리되는지 확인"
