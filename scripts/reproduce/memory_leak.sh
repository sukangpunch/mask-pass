#!/bin/bash
# ============================================================
# memory_leak.sh — emitter 정리 & lastKnownCounts 상태 보존 검증
#
# 목적:
#   연결 종료 후 올바른 상태를 확인한다.
#
#   [정상 동작]
#     emitter         → 종료 시 Map에서 제거되어야 함 (emitter=0)
#     lastKnownCounts → 종료 후에도 유지되어야 함 (lastKnown=N)
#                       재연결 시 이전 카운트를 초기 이벤트로 전송하기 위한 상태 캐시
#
#   [Phase 1] 4개 구독 → 정상 종료
#     기대: emitter=0 (정리됨), lastKnown=4 (보존) ← 둘 다 정상
#
#   [Phase 2] 동일 키로 재연결
#     기대: emitter=4 (새 연결), lastKnown=4 (이전 상태 유지)
#     → 재연결 시 초기 이벤트 count가 0이 아님을 서버 로그로 확인
#
#   [Bug 확인 대상]
#     emitter가 종료 후에도 남아있는 경우(zombie) → zombie_socket.sh 참조
#     lastKnownCounts가 0으로 초기화된 경우 → 재연결 시 count 소실 버그
#
# 사용법: bash scripts/reproduce/memory_leak.sh
# ============================================================
HOST="http://localhost:8080"
CONF=1
SESSION_IDS=(1 2 3)

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

heap_mb() {
    HEAP=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null \
        | grep -o '"value":[0-9.E+\-]*' | head -1 | grep -o '[0-9.E+\-]*')
    if [ -z "${HEAP}" ]; then
        echo "?"
    else
        awk "BEGIN {printf \"%.1f\", ${HEAP} / 1048576}"
    fi
}
get_emitter()    { curl -s "${HOST}/api/v1/sse/status" 2>/dev/null | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*'; }
get_last_known() { curl -s "${HOST}/api/v1/sse/status" 2>/dev/null | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*'; }

echo "========================================================"
echo " emitter 정리 & lastKnownCounts 상태 보존 검증"
echo " 대상: conference:${CONF} + session:1,2,3 (총 4개)"
echo "========================================================"
echo ""
echo -e "${CYN}[초기 상태]${RST}"
echo "  emitter=$(get_emitter) | lastKnown=$(get_last_known) | heap=$(heap_mb)MB"

# ── Phase 1: 4개 구독 → 정상 종료 ─────────────────────────────
echo ""
echo -e "${YLW}[Phase 1] 4개 구독 → 정상 종료${RST}"
echo "  (unsubscribe API 호출 = 서버에서 complete() → onCompletion 발화)"
echo ""

PHASE1_PIDS=()
curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
    -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
PHASE1_PIDS+=($!)
for sid in "${SESSION_IDS[@]}"; do
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    PHASE1_PIDS+=($!)
done
sleep 0.5
echo "  연결 후: emitter=$(get_emitter) | lastKnown=$(get_last_known)"

# 정상 종료 (unsubscribe API)
curl -s -X DELETE "${HOST}/api/v1/sse/unsubscribe?conferenceId=${CONF}" > /dev/null
for sid in "${SESSION_IDS[@]}"; do
    curl -s -X DELETE "${HOST}/api/v1/sse/unsubscribe?conferenceId=${CONF}&sessionId=${sid}" > /dev/null
done
for pid in "${PHASE1_PIDS[@]}"; do kill "$pid" 2>/dev/null; done
sleep 0.5

EMITTER_AFTER=$(get_emitter)
LAST_KNOWN_AFTER=$(get_last_known)
echo "  종료 후:"
printf "    emitter      = %s  (기대: 0 — 정리됨)\n" "${EMITTER_AFTER:-?}"
printf "    lastKnown    = %s  (기대: 4 — 재연결용 상태 보존, 정상!)\n" "${LAST_KNOWN_AFTER:-?}"
echo ""

if [ "${EMITTER_AFTER:-1}" -eq 0 ] 2>/dev/null; then
    echo -e "  ${GRN}[PASS] emitter 정상 정리됨${RST}"
else
    echo -e "  ${RED}[BUG] emitter=${EMITTER_AFTER} 잔류 — zombie 소켓 발생!${RST}"
    echo "   → zombie_socket.sh 로 상세 확인"
fi

if [ "${LAST_KNOWN_AFTER:-0}" -eq 4 ] 2>/dev/null; then
    echo -e "  ${GRN}[PASS] lastKnownCounts=4 보존 — 재연결 시 이전 카운트 전송 가능${RST}"
elif [ "${LAST_KNOWN_AFTER:-0}" -eq 0 ] 2>/dev/null; then
    echo -e "  ${RED}[BUG] lastKnownCounts=0 — 재연결 시 count가 0으로 초기화됨!${RST}"
    echo "   → onCompletion/onTimeout/onError에서 lastKnownCounts.remove() 호출 여부 확인"
fi

# ── Phase 2: 동일 키로 재연결 → 상태 연속성 확인 ─────────────
echo ""
echo -e "${YLW}[Phase 2] 동일 키로 재연결 — 상태 연속성 확인${RST}"
echo "  (lastKnownCounts에 이전 값이 있으면 재연결 초기 이벤트에서 count=0 대신 이전 값 전송)"
echo ""

PHASE2_PIDS=()
curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
    -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
PHASE2_PIDS+=($!)
for sid in "${SESSION_IDS[@]}"; do
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    PHASE2_PIDS+=($!)
done
sleep 0.5

EMITTER_RECONNECT=$(get_emitter)
LAST_KNOWN_RECONNECT=$(get_last_known)
echo "  재연결 후: emitter=${EMITTER_RECONNECT:-?} | lastKnown=${LAST_KNOWN_RECONNECT:-?}"
echo ""

if [ "${EMITTER_RECONNECT:-0}" -eq 4 ] && [ "${LAST_KNOWN_RECONNECT:-0}" -eq 4 ] 2>/dev/null; then
    echo -e "  ${GRN}[PASS] 재연결 성공, lastKnownCounts 상태 유지${RST}"
    echo "   → 서버 로그에서 sendToClientFirst() 초기 count 값 확인"
    echo "     count > 0 이면 상태 연속성 정상, count = 0 이면 lastKnownCounts 소실"
fi

# 정리
for pid in "${PHASE2_PIDS[@]}"; do kill "$pid" 2>/dev/null; done
curl -s -X DELETE "${HOST}/api/v1/sse/unsubscribe?conferenceId=${CONF}" > /dev/null
for sid in "${SESSION_IDS[@]}"; do
    curl -s -X DELETE "${HOST}/api/v1/sse/unsubscribe?conferenceId=${CONF}&sessionId=${sid}" > /dev/null
done

echo ""
echo "========================================================"
echo " 요약"
echo "  emitter=0    : 정상 (zombie 없음)"
echo "  lastKnown=4  : 정상 (재연결 상태 보존 — 의도된 동작)"
echo "  lastKnown=0  : 버그 (재연결 시 count 소실)"
echo ""
echo " zombie 소켓 테스트 : bash scripts/reproduce/zombie_socket.sh"
echo " 새로고침 시뮬레이션: bash scripts/reproduce/refresh_simulation.sh"
echo "========================================================"
