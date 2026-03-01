#!/bin/bash
# =============================================================
# zombie_socket.sh — 좀비 소켓(CLOSE_WAIT) 재연
#
# 시나리오:
#   1. 여러 개의 SSE 연결을 SIGKILL로 강제 종료 (FIN 없이)
#   2. 서버가 CLOSE_WAIT 상태의 연결을 감지 못하는지 확인
#   3. emitter가 Map에 잔류하는 것을 /api/v1/sse/status로 확인
#
# 사용법: bash scripts/reproduce/zombie_socket.sh [connection_count]
# 예시:   bash scripts/reproduce/zombie_socket.sh 5
# =============================================================
HOST="http://localhost:8080"
COUNT="${1:-5}"

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
RST='\033[0m'

echo "========================================================"
echo " [Bug-1/6] 좀비 소켓 재연 — ${COUNT}개 연결 후 SIGKILL"
echo "========================================================"

# ── Step 1: 연결 전 상태 확인 ──────────────────────────────
echo ""
echo -e "${YLW}[STEP 1] 연결 전 emitter 상태${RST}"
curl -s "${HOST}/api/v1/sse/status" | python3 -m json.tool 2>/dev/null || \
  curl -s "${HOST}/api/v1/sse/status"

# ── Step 2: N개 SSE 연결 (각각 다른 conferenceId) ──────────
echo ""
echo -e "${YLW}[STEP 2] ${COUNT}개 SSE 연결 시작...${RST}"

PIDS=()
for i in $(seq 1 "$COUNT"); do
  curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${i}" \
    -H "Accept: text/event-stream" \
    --no-buffer > /dev/null 2>&1 &
  PIDS+=($!)
  echo "  연결 ${i}: curl PID=${PIDS[-1]} (conferenceId=${i})"
done

sleep 1  # 서버가 emitter 등록할 시간

echo ""
echo -e "${YLW}[STEP 2] 연결 후 emitter 상태${RST}"
curl -s "${HOST}/api/v1/sse/status" | python3 -m json.tool 2>/dev/null || \
  curl -s "${HOST}/api/v1/sse/status"

# ── Step 3: SIGKILL로 강제 종료 (FIN 없이 = CLOSE_WAIT 시뮬레이션) ──
echo ""
echo -e "${YLW}[STEP 3] SIGKILL로 ${COUNT}개 연결 강제 종료 (프론트 탭 닫기 시뮬레이션)${RST}"
for pid in "${PIDS[@]}"; do
  kill -9 "$pid" 2>/dev/null
  echo "  kill -9 ${pid}"
done

# ── Step 4: 서버가 감지하길 대기 ───────────────────────────
echo ""
echo "  5초 대기 중 (onError/onCompletion 발화 여부 확인)..."
sleep 5

# ── Step 5: 종료 후 emitter 상태 확인 ──────────────────────
echo ""
echo -e "${YLW}[STEP 5] SIGKILL 후 emitter 상태${RST}"
AFTER=$(curl -s "${HOST}/api/v1/sse/status")
echo "$AFTER" | python3 -m json.tool 2>/dev/null || echo "$AFTER"

EMITTER_COUNT=$(echo "$AFTER" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
LAST_KNOWN=$(echo "$AFTER" | grep -o '"lastKnownCountsSize":[0-9]*' | grep -o '[0-9]*')

echo ""
echo "========================================================"
echo -e " 결과 요약"
echo -e "  emitterCount       : ${RED}${EMITTER_COUNT}${RST}  (기대값: 0, 수정 전: ${COUNT})"
echo -e "  lastKnownCounts 수 : ${RED}${LAST_KNOWN}${RST}   (기대값: 0, 수정 전: ${COUNT})"
echo ""
if [ "${EMITTER_COUNT}" -eq 0 ] 2>/dev/null; then
  echo -e "  ${GRN}[PASS] emitter 정상 정리됨 (수정 후 동작)${RST}"
else
  echo -e "  ${RED}[BUG 확인] emitter ${EMITTER_COUNT}개 잔류 — 좀비 소켓 발생!${RST}"
  echo -e "  ${RED}           server 로그에서 onError/onCompletion 미발화 확인${RST}"
fi
echo "========================================================"
