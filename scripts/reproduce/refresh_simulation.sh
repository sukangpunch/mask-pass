#!/bin/bash
# ============================================================
# refresh_simulation.sh — 브라우저 새로고침 반복 → 좀비 소켓·OOM 재연
#
# 시나리오:
#   컨퍼런스 현장 태블릿 1대가 화면을 새로고침할 때 =
#   "이전 탭 강제 종료(SIGKILL) → 즉시 재연결" 사이클 반복
#
#   구독 대상 (총 4개):
#     conference:1             ← 전체 카운트 모니터
#     conference:1:session:1   ← 세션 1 카운트 모니터
#     conference:1:session:2   ← 세션 2 카운트 모니터
#     conference:1:session:3   ← 세션 3 카운트 모니터
#
#   각 사이클:
#     1. 4개 연결
#     2. SIGKILL (브라우저 탭 강제 닫기)
#     3. [zombie_wait]초 대기 — 이 시간 동안 좀비 상태
#     4. 재연결
#     → 반복 후 최종 좀비 잔류 여부 확인
#
# 사용법: bash scripts/reproduce/refresh_simulation.sh [rounds] [zombie_wait_sec]
# 예시:   bash scripts/reproduce/refresh_simulation.sh 5 3
#
# 수정 전 기대: SIGKILL 후 emitter=4 유지 (zombie!)
#               재연결 시 서버가 ping IOException → 정리 → 새 emitter 등록
#               → 각 사이클에서 zombie_wait 시간만큼 자원 점유 (CLOSE_WAIT)
# 수정 후 기대: heartbeat으로 zombie를 reconnect 없이도 즉시 감지·정리
# ============================================================
HOST="http://localhost:8080"
ROUNDS="${1:-5}"
ZOMBIE_WAIT="${2:-3}"   # SIGKILL 후 zombie 상태 유지 시간 (초)
CONF=1
SESSION_IDS=(1 2 3)
TOTAL=4

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

PIDS=()

# ── 헬퍼 ─────────────────────────────────────────────────────
heap_mb() {
    HEAP=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null \
        | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')
    echo "scale=1; ${HEAP:-0} / 1048576" | bc 2>/dev/null || echo "?"
}

get_status() {
    S=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
    E=$(echo "$S" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
    L=$(echo "$S" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')
    printf "emitter=%-2s | lastKnown=%-2s | heap=%sMB" "${E:-?}" "${L:-?}" "$(heap_mb)"
}

connect_all() {
    PIDS=()
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    PIDS+=($!)
    for sid in "${SESSION_IDS[@]}"; do
        curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
            -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
        PIDS+=($!)
    done
}

kill_all() {
    for pid in "${PIDS[@]}"; do
        kill -9 "$pid" 2>/dev/null
    done
}

# ── 메인 ─────────────────────────────────────────────────────
echo "================================================================"
echo " 브라우저 새로고침 시뮬레이션"
echo " 대상 : conference:${CONF} + session:1,2,3 (${TOTAL}개 스트림)"
echo " 사이클: 4개 연결 → SIGKILL → ${ZOMBIE_WAIT}초 zombie → 재연결 (${ROUNDS}회)"
echo "================================================================"
echo ""
echo -e "${CYN}[초기] $(get_status)${RST}"
echo ""

ZOMBIE_TOTAL_SEC=0

for round in $(seq 1 "${ROUNDS}"); do
    echo -e "${YLW}━━━ Round ${round}/${ROUNDS} ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${RST}"

    # Step A: 4개 연결
    connect_all
    sleep 0.5
    echo -e "  ${GRN}[연결 완료]${RST} $(get_status)"

    # Step B: SIGKILL (브라우저 새로고침 = 이전 탭 강제 종료)
    kill_all
    sleep 0.2
    echo -e "  ${RED}[SIGKILL  ]${RST} $(get_status)  ← zombie 시작"

    # Step C: Zombie 상태 유지 관찰
    ZOMBIE_TOTAL_SEC=$((ZOMBIE_TOTAL_SEC + ZOMBIE_WAIT))
    sleep "${ZOMBIE_WAIT}"
    echo -e "  ${RED}[${ZOMBIE_WAIT}초 후   ]${RST} $(get_status)  ← zombie 지속 (Tomcat CLOSE_WAIT)"
done

# 마지막 라운드 후 reconnect 없이 최종 상태 확인
echo ""
echo -e "${CYN}[최종 상태] $(get_status)${RST}"
echo ""

FINAL_S=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
FINAL_E=$(echo "$FINAL_S" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
FINAL_L=$(echo "$FINAL_S" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')

echo "================================================================"
echo " 결과 요약"
echo ""
echo "  총 새로고침 횟수        : ${ROUNDS}회"
echo "  zombie 상태 누적 시간   : 약 ${ZOMBIE_TOTAL_SEC}초 (${ROUNDS}회 × ${ZOMBIE_WAIT}초)"
echo "  최종 emitterCount       : ${FINAL_E:-?}  (기대: 0 — 마지막 SIGKILL 후 감지 여부)"
echo "  최종 lastKnownCounts    : ${FINAL_L:-?}"
echo ""
if [ "${FINAL_E}" -eq 0 ] 2>/dev/null; then
    echo -e "  ${GRN}[PASS] 마지막 SIGKILL 후 emitter 정리됨${RST}"
    echo "   → onError 즉시 발화하거나 heartbeat이 감지함"
else
    echo -e "  ${RED}[BUG] ${FINAL_E}개 zombie 잔류!${RST}"
    echo "   → SIGKILL ~ 재연결 사이 ${ZOMBIE_WAIT}초 동안 Tomcat 스레드/소켓 점유"
    echo "   → 재연결 없이 방치 시 1800초(timeout)까지 자원 점유 지속"
    echo "   → 해결책: heartbeat 도입으로 zombie 능동 감지·정리"
fi
echo "================================================================"
echo ""
echo " 다음 단계:"
echo "   bash scripts/reproduce/race_condition.sh   → 레이스 컨디션 재연"
echo "   bash scripts/reproduce/oom_simulation.sh   → 장기 OOM 경향 확인"
