#!/bin/bash
# ============================================================
# race_condition.sh — subscribe() Race Condition 재연
#
# 시나리오:
#   새로고침 = 이전 연결 종료 직후 동시에 새 연결 요청
#   4개 스트림(conference:1 + session:1,2,3)에 대해
#   동일 eventKey로 거의 동시에 2개의 subscribe 요청이 오면:
#
#     Thread A: findEmitter("conference:1") → null
#     Thread B: findEmitter("conference:1") → null (A가 save하기 전)
#     Thread A: save("conference:1", emitterA)
#     Thread B: save("conference:1", emitterB) → emitterA를 덮어씀! (leak)
#
#   → emitter 개수가 4를 초과하면 race condition 발생
#   → 또는 duplicate complete() 호출로 IllegalStateException 발생 가능
#
# 사용법: bash scripts/reproduce/race_condition.sh [repeat]
# 예시:   bash scripts/reproduce/race_condition.sh 20
#
# 재현율:
#   CPU 속도에 따라 항상 발생하지 않음
#   repeat 횟수를 늘리거나 서버에 부하를 주면 재현율 상승
# ============================================================
HOST="http://localhost:8080"
REPEAT="${1:-20}"
CONF=1
SESSION_IDS=(1 2 3)

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
RST='\033[0m'

ALL_PIDS=()
MAX_EMITTER=0

get_emitter() {
    curl -s "${HOST}/api/v1/sse/status" 2>/dev/null \
        | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*'
}

get_status() {
    S=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
    E=$(echo "$S" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
    L=$(echo "$S" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')
    echo "emitter=${E:-?} | lastKnownCounts=${L:-?}"
}

# ── 4개 스트림 초기 연결 ───────────────────────────────────────
echo "========================================================"
echo " [Bug] subscribe() Race Condition 재연"
echo " conference:${CONF} + session:1,2,3 — 4개 동시 재연결 ${REPEAT}회"
echo "========================================================"

echo ""
echo -e "${YLW}[STEP 1] 초기 4개 SSE 연결${RST}"
PREV_PIDS=()
curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
    -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
PREV_PIDS+=($!)
for sid in "${SESSION_IDS[@]}"; do
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    PREV_PIDS+=($!)
done
ALL_PIDS+=("${PREV_PIDS[@]}")
sleep 0.5
echo "  연결 후: $(get_status)  (기대: emitter=4)"

# ── 동시 재연결 반복 ─────────────────────────────────────────
echo ""
echo -e "${YLW}[STEP 2] 동시 재연결 ${REPEAT}회 반복 (race condition 유발)${RST}"
echo "  이전 연결 종료와 새 연결을 거의 동시에 실행..."
echo ""

RACE_DETECTED=0
for i in $(seq 1 "$REPEAT"); do
    NEW_PIDS=()

    # 이전 연결 종료 + 새 연결을 동시에 시작 (race window 생성)
    for pid in "${PREV_PIDS[@]}"; do
        kill "$pid" 2>/dev/null
    done

    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    NEW_PIDS+=($!)
    for sid in "${SESSION_IDS[@]}"; do
        curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
            -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
        NEW_PIDS+=($!)
    done

    sleep 0.08   # race window: kill과 새 연결 사이 최소 간격
    ALL_PIDS+=("${NEW_PIDS[@]}")
    PREV_PIDS=("${NEW_PIDS[@]}")

    CURRENT=$(get_emitter)
    if [ "${CURRENT:-0}" -gt 4 ] 2>/dev/null; then
        echo -e "  [Round ${i}] ${RED}emitter=${CURRENT} — RACE CONDITION 발생!${RST}"
        RACE_DETECTED=1
    fi

    # 최댓값 갱신
    if [ "${CURRENT:-0}" -gt "${MAX_EMITTER}" ] 2>/dev/null; then
        MAX_EMITTER="${CURRENT:-0}"
    fi
done

sleep 0.5
echo ""
echo -e "${YLW}[STEP 3] 재연결 완료 후 상태${RST}"
STATUS=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
echo "$STATUS" | python3 -m json.tool 2>/dev/null || echo "$STATUS"

EMITTER_COUNT=$(echo "$STATUS" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')

echo ""
echo "========================================================"
echo " 결과"
echo "  최종 emitterCount : ${EMITTER_COUNT:-?}  (기대: 4, race 시: >4)"
echo "  관찰 최대 emitter : ${MAX_EMITTER}  (4 초과 시 race condition)"
echo ""
if [ "${RACE_DETECTED}" -eq 1 ]; then
    echo -e "  ${RED}[BUG 확인] Race Condition 발생! emitter 누수 또는 중복 등록${RST}"
    echo "   → 수정: getAndReplace()로 원자적 교체"
elif [ "${EMITTER_COUNT}" -eq 4 ] 2>/dev/null; then
    echo -e "  ${GRN}[PASS] emitter 4개 유지 — race condition 미발생${RST}"
    echo "   (재현율이 낮을 수 있음 — repeat 횟수를 늘리거나 서버 부하 증가)"
else
    echo -e "  ${RED}[BUG] emitter=${EMITTER_COUNT} — 비정상 상태${RST}"
fi
echo "========================================================"

# 정리
for pid in "${ALL_PIDS[@]}"; do kill "$pid" 2>/dev/null; done
