#!/bin/bash
# ============================================================
# oom_simulation.sh — 장기 OOM 경향 시뮬레이션
#
# 시나리오:
#   실제 현장: 컨퍼런스마다 태블릿 1대가 SSE 구독
#   네트워크 단절/태블릿 전원 OFF → 재연결 없음 → 좀비 누적
#
#   [기본 4개] conference:1 + session:1,2,3 을 반복 kill (재연결 없음)
#     → 같은 key이므로 다음 연결 시 교체되지만,
#     → 재연결 없이 방치 시 1800초(30분) timeout까지 자원 점유
#
#   [확장] 다른 conferenceId 사용 (다수 컨퍼런스 시뮬레이션)
#     → 각 kill마다 새로운 zombie 키 추가 → emitter 누적 확인
#
# 사용법: bash scripts/reproduce/oom_simulation.sh [conn_per_round] [rounds]
# 예시:   bash scripts/reproduce/oom_simulation.sh 4 5
#         → 기본 4개(conf:1 + session:1,2,3) × 5라운드 (재연결 없이 kill)
#
# 실제 OOM: 수천 개 연결이 필요하지만, 여기서는 경향을 수치로 확인
# ============================================================
HOST="http://localhost:8080"
ROUNDS="${2:-5}"
CONF=1
SESSION_IDS=(1 2 3)
TOTAL=4   # 4개 고정 스트림

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

PIDS_POOL=()

heap_mb() {
    HEAP=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null \
        | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')
    echo "scale=1; ${HEAP:-0} / 1048576" | bc 2>/dev/null || echo "?"
}

get_status() {
    S=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
    E=$(echo "$S" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
    L=$(echo "$S" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')
    printf "emitter=%-3s | lastKnown=%-3s | heap=%sMB" "${E:-?}" "${L:-?}" "$(heap_mb)"
}

connect_fixed_4() {
    local ROUND_PIDS=()
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    ROUND_PIDS+=($!)
    for sid in "${SESSION_IDS[@]}"; do
        curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
            -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
        ROUND_PIDS+=($!)
    done
    PIDS_POOL+=("${ROUND_PIDS[@]}")
    printf '%s ' "${ROUND_PIDS[@]}"
}

# ══════════════════════════════════════════════════════════════
# Phase A: 같은 4개 스트림 반복 SIGKILL (재연결 없음)
#   → emitterCount 최대 4 (같은 key이므로 누적 안 됨)
#   → 하지만 SIGKILL ~ timeout(1800s) 동안 CLOSE_WAIT 소켓 점유
# ══════════════════════════════════════════════════════════════
echo "========================================================"
echo " OOM 경향 시뮬레이션"
echo " Phase A: conference:${CONF} + session:1,2,3 (${ROUNDS}라운드, 재연결 없음)"
echo " Phase B: 다른 conferenceId로 좀비 누적 확인"
echo "========================================================"

echo ""
echo -e "${CYN}[초기] $(get_status)${RST}"
echo ""

echo -e "${YLW}━━━ Phase A: 고정 4개 스트림 반복 SIGKILL ━━━${RST}"
echo " (같은 key라 emitter Map은 최대 4 — TCP/소켓 자원 점유 관찰)"
echo ""

for round in $(seq 1 "$ROUNDS"); do
    echo -e "${YLW}[Round A-${round}/${ROUNDS}]${RST}"

    # 연결
    ROUND_PIDS_STR=$(connect_fixed_4)
    sleep 0.5
    echo "  연결 후 : $(get_status)"

    # SIGKILL (재연결 없음)
    for pid in ${ROUND_PIDS_STR}; do
        kill -9 "$pid" 2>/dev/null
    done
    sleep 3
    echo -e "  SIGKILL : ${RED}$(get_status)${RST}  ← zombie (재연결 없으므로 cleanup 안 됨)"
    echo ""
done

# ══════════════════════════════════════════════════════════════
# Phase B: 다른 conferenceId로 다수 연결 → 누적 확인
#   → 실제 OOM 시나리오: 수십 개 컨퍼런스에서 태블릿 단절
# ══════════════════════════════════════════════════════════════
echo ""
echo -e "${YLW}━━━ Phase B: 다른 conferenceId ${ROUNDS}개 누적 SIGKILL ━━━${RST}"
echo " (고유 key마다 새 emitter 추가 → 재연결 없으면 무한 누적)"
echo ""

PHASE_B_BASE=100

for round in $(seq 1 "$ROUNDS"); do
    FAKE_CONF=$((PHASE_B_BASE + round))
    echo -e "${YLW}[Round B-${round}/${ROUNDS}] conferenceId=${FAKE_CONF}${RST}"

    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${FAKE_CONF}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    FAKE_PID=$!
    PIDS_POOL+=("$FAKE_PID")
    sleep 0.5
    echo "  연결 후 : $(get_status)"

    kill -9 "$FAKE_PID" 2>/dev/null
    sleep 3
    echo -e "  SIGKILL : ${RED}$(get_status)${RST}  ← emitter 누적 중"
    echo ""
done

echo ""
echo "========================================================"
FINAL_S=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
FINAL_E=$(echo "$FINAL_S" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
FINAL_L=$(echo "$FINAL_S" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')

echo " 최종 결과 (Phase A + Phase B 후)"
echo "  emitterCount       : ${FINAL_E:-?}"
echo "  lastKnownCounts    : ${FINAL_L:-?}"
echo "  heap               : $(heap_mb)MB"
echo ""
echo " Phase B zombie 기대값: ${ROUNDS}개 (1개씩 누적)"
echo " Phase A zombie 기대값: 최대 4개 (key 재사용, 재연결 시 교체)"
echo ""
if [ "${FINAL_E:-0}" -le 4 ] 2>/dev/null; then
    echo -e "  ${GRN}[비교적 정상] emitter=${FINAL_E:-?} (Phase B 좀비가 정리됐거나 아직 관찰 중)${RST}"
else
    echo -e "  ${RED}[OOM 경향] emitter=${FINAL_E:-?} 잔류 — 장기 운영 시 OOM 위험${RST}"
fi
echo ""
echo " 실제 OOM 재현:"
echo "   oom_simulation.sh 20 10  → 20개 고유 conferenceId × 10라운드 = 200좀비"
echo "========================================================"

# 정리 (모니터링 종료 후 수동 cleanup)
for pid in "${PIDS_POOL[@]}"; do kill "$pid" 2>/dev/null; done
