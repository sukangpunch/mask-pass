#!/bin/bash
# ============================================================
# memory_leak.sh — lastKnownCounts 메모리 누수 재연
#
# 시나리오:
#   conference:1 + session:1,2,3 (4개)를 구독 후 정상 종료
#   → emitter는 onCompletion으로 Map에서 제거되지만
#   → lastKnownCounts Map에는 키가 영구 잔류 (버그)
#   → 반복 시 Map이 누적 증가 (메모리 누수)
#
#   [1단계] 4개 고정 스트림 테스트 (conf:1 + session:1,2,3)
#     emitter 종료 후 lastKnownCounts=4 잔류 확인
#
#   [2단계] 다른 conferenceId 순환 구독
#     각 고유 key마다 새 lastKnownCounts 항목 추가
#     → ITER개 항목 누적 확인
#
# 사용법: bash scripts/reproduce/memory_leak.sh [iter]
# 예시:   bash scripts/reproduce/memory_leak.sh 30
# ============================================================
HOST="http://localhost:8080"
ITER="${1:-30}"
CONF=1
SESSION_IDS=(1 2 3)

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

heap_mb() {
    VAL=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null \
        | grep -o '"value":[0-9.]*' | head -1 | grep -o '[0-9.]*')
    echo "scale=1; ${VAL:-0} / 1048576" | bc 2>/dev/null || echo "?"
}
get_emitter()    { curl -s "${HOST}/api/v1/sse/status" 2>/dev/null | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*'; }
get_last_known() { curl -s "${HOST}/api/v1/sse/status" 2>/dev/null | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*'; }

# ── Phase 1: 4개 고정 스트림 ───────────────────────────────────
echo "========================================================"
echo " [Bug] lastKnownCounts 메모리 누수 재연"
echo " 대상: conference:${CONF} + session:1,2,3 + 추가 ${ITER}개 순환"
echo "========================================================"

echo ""
echo -e "${YLW}[Phase 1] conference:${CONF} + session:1,2,3 — 4개 구독 후 정상 종료${RST}"
HEAP_BEFORE=$(heap_mb)
echo "  초기: emitter=$(get_emitter) | lastKnown=$(get_last_known) | heap=${HEAP_BEFORE}MB"

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

echo -e "  종료 후: emitter=$(get_emitter) | ${RED}lastKnown=$(get_last_known)${RST}  ← 4 남으면 버그!"

# ── Phase 2: 다른 conferenceId 순환 (누수 규모 확인) ────────────
echo ""
echo -e "${YLW}[Phase 2] 다른 conferenceId 순환 구독 → 해제 (${ITER}회)${RST}"
echo "  (각 고유 eventKey마다 lastKnownCounts에 새 항목 추가됨)"
echo ""

HEAP_P2_BEFORE=$(heap_mb)

for i in $(seq 1 "$ITER"); do
    # 다른 conferenceId 사용 (10부터 시작, 실제 DB 데이터와 무관)
    FAKE_CONF=$((10 + i))
    CURL_PID=""
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${FAKE_CONF}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    CURL_PID=$!
    sleep 0.15

    # 정상 종료
    curl -s -X DELETE "${HOST}/api/v1/sse/unsubscribe?conferenceId=${FAKE_CONF}" > /dev/null 2>&1
    kill "$CURL_PID" 2>/dev/null
    sleep 0.05

    if [ $((i % 10)) -eq 0 ]; then
        printf "  [%2d/%d] lastKnown=%s | emitter=%s | heap=%sMB\n" \
            "$i" "$ITER" "$(get_last_known)" "$(get_emitter)" "$(heap_mb)"
    fi
done

echo ""
echo -e "${YLW}[최종 상태]${RST}"
EMITTER_FINAL=$(get_emitter)
LAST_KNOWN_FINAL=$(get_last_known)
HEAP_AFTER=$(heap_mb)

echo "  emitterCount      : ${EMITTER_FINAL:-?}  (기대: 0)"
echo -e "  lastKnownCounts   : ${RED}${LAST_KNOWN_FINAL:-?}${RST}  (기대: 0, 버그 시: $((4 + ITER)))"
echo "  Heap 변화 (Phase1 전): ${HEAP_BEFORE}MB → (Phase2 후): ${HEAP_AFTER}MB"

echo ""
echo "========================================================"
EXPECTED_LEAK=$((4 + ITER))
if [ "${LAST_KNOWN_FINAL:-0}" -eq 0 ] 2>/dev/null; then
    echo -e "  ${GRN}[PASS] lastKnownCounts 정상 정리됨 (수정 후 동작)${RST}"
else
    echo -e "  ${RED}[BUG] lastKnownCounts에 ${LAST_KNOWN_FINAL}개 잔류 (기대 누수량: ${EXPECTED_LEAK})${RST}"
    echo "   → emitter 종료 시 lastKnownCounts.remove() 누락"
    echo "   → 수정: onCompletion/onTimeout/onError에 remove() 추가"
fi
echo "========================================================"
