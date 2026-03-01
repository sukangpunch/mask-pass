#!/bin/bash
# =============================================================
# race_condition.sh — 동시 재연결 Race Condition 재연
#
# 시나리오:
#   프론트 새로고침 = 동일 conferenceId로 빠르게 재연결
#   두 요청이 동시에 들어오면:
#     - 기존 emitter가 complete() 처리 없이 교체될 수 있음
#     - 또는 두 emitter가 동시에 Map에 등록됨 (개수 불일치)
#
# 사용법: bash scripts/reproduce/race_condition.sh
# =============================================================
HOST="http://localhost:8080"
CONFERENCE_ID=1

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
RST='\033[0m'

echo "========================================================"
echo " [Bug-1] subscribe() Race Condition 재연"
echo " conferenceId=${CONFERENCE_ID} 로 동시 재연결 반복"
echo "========================================================"

# ── Step 1: 초기 연결 ───────────────────────────────────────
echo ""
echo -e "${YLW}[STEP 1] 초기 SSE 연결 (conferenceId=${CONFERENCE_ID})${RST}"
curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONFERENCE_ID}" \
  -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
PID1=$!
sleep 0.5
echo "  PID=${PID1} 연결 완료"
echo "  현재 emitter 수: $(curl -s ${HOST}/api/v1/sse/status | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')"

# ── Step 2: 동시 재연결 (새로고침 시뮬레이션) ────────────────
echo ""
echo -e "${YLW}[STEP 2] 동시 재연결 10회 반복 (새로고침 시뮬레이션)${RST}"

PIDS=()
for i in $(seq 1 10); do
  # 이전 연결을 종료하고 즉시 새 연결 (거의 동시)
  curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONFERENCE_ID}" \
    -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
  NEW_PID=$!
  PIDS+=("$NEW_PID")

  # 이전 PID 종료
  kill "$PID1" 2>/dev/null
  PID1=$NEW_PID
  sleep 0.1
done

sleep 1

echo ""
echo -e "${YLW}[STEP 2] 재연결 완료 후 상태${RST}"
STATUS=$(curl -s "${HOST}/api/v1/sse/status")
echo "$STATUS" | python3 -m json.tool 2>/dev/null || echo "$STATUS"

EMITTER_COUNT=$(echo "$STATUS" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')

echo ""
echo "========================================================"
echo " 결과"
echo -e "  emitterCount: ${RED}${EMITTER_COUNT}${RST}  (기대: 1, race 발생 시: >1 또는 0)"
if [ "${EMITTER_COUNT}" -eq 1 ] 2>/dev/null; then
  echo -e "  ${GRN}[PASS] emitter 1개만 유지됨${RST}"
else
  echo -e "  ${RED}[BUG 확인] emitter ${EMITTER_COUNT}개 — race condition 발생!${RST}"
fi
echo "========================================================"

# 정리
for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null; done
