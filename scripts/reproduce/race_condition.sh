#!/bin/bash
# ============================================================
# race_condition.sh — subscribe() Race Condition 재연 (개선판)
#
# 재현 대상 취약 구간 (SseService.subscribe):
#
#   emitter = emitterRepository.findEmitterByKey(eventKey)  ← (1) read
#   if (emitter != null) { emitter.complete(); delete(); }
#   emitterRepository.save(eventKey, new SseEmitter())      ← (2) write
#
#   (1)과 (2) 사이가 atomic하지 않음.
#   동일 eventKey로 두 요청이 동시에 (1)을 통과하면:
#     - 둘 다 null을 읽음
#     - 둘 다 save() 호출 → 나중에 저장된 emitter가 앞의 것을 덮어씀
#     - 덮어써진 emitter는 Map에서 참조가 끊긴 채 힙에 잔류 (leak)
#
# 판정 기준:
#   - emitter > 4          : race로 인한 중복 등록 (ConcurrentHashMap 특성상 포착 어려움)
#   - 힙 증가 추세         : 덮어써진 emitter가 GC 대상이 되지 않고 잔류 (주 판정 기준)
#   - 서버 로그 IllegalStateException : complete()가 이미 완료된 emitter에 재호출
#
# 동작 방식:
#   각 라운드마다 4개 eventKey × PARALLEL개 요청을 전부 동시에 발사 (&)
#   → $() 명령 치환 없이 직접 백그라운드 실행하여 진짜 병렬 보장
#   → subscribe()의 findEmitter()~save() 비원자 구간에 복수 쓰레드 동시 진입 유도
#
# 사용법: bash scripts/reproduce/race_condition.sh [repeat] [parallel]
# 예시:   bash scripts/reproduce/race_condition.sh 30 2
#   repeat  : 동시 재연결 반복 횟수 (기본 30)
#   parallel: 동일 eventKey에 동시 발사할 요청 수 (기본 2, race window 생성 핵심)
#
# 재현율이 낮을 경우:
#   repeat 수를 늘리거나 parallel을 3 이상으로 설정
#   예시: bash scripts/reproduce/race_condition.sh 50 3
# ============================================================

HOST="http://localhost:8080"
REPEAT="${1:-30}"
PARALLEL="${2:-2}"
CONF=1
SESSION_IDS=(1 2 3)

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
CYN='\033[0;36m'
RST='\033[0m'

ALL_PIDS=()
MAX_EMITTER=0
RACE_COUNT=0
LEAK_DETECTED=0

# ── 헬퍼 ─────────────────────────────────────────────────────
heap_mb() {
    HEAP=$(curl -s "${HOST}/actuator/metrics/jvm.memory.used?tag=area:heap" 2>/dev/null \
        | grep -o '"value":[0-9.E+\-]*' | head -1 | grep -o '[0-9.E+\-]*')
    if [ -z "${HEAP}" ]; then echo "?"; else
        awk "BEGIN {printf \"%.1f\", ${HEAP} / 1048576}"
    fi
}

get_status() {
    S=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
    E=$(echo "$S" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')
    printf "emitter=%-2s | heap=%sMB" "${E:-?}" "$(heap_mb)"
}

get_emitter_count() {
    curl -s "${HOST}/api/v1/sse/status" 2>/dev/null \
        | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*'
}

cleanup_pids() {
    for pid in "$@"; do
        kill "$pid" 2>/dev/null
    done
}

# ── 메인 ─────────────────────────────────────────────────────
echo "========================================================"
echo " [Bug] subscribe() Race Condition 재연 (개선판)"
echo " 전략: 4개 eventKey × ${PARALLEL}개 요청 전부 동시 발사 × ${REPEAT}회"
echo " 취약 구간: findEmitter() → save() 비원자 구간"
echo "========================================================"
echo ""
echo -e "${CYN}[초기 상태] $(get_status)${RST}"
echo ""

HEAP_START=$(heap_mb)

# ── STEP 1: 정상 초기 연결 (베이스라인) ─────────────────────
echo -e "${YLW}[STEP 1] 정상 초기 연결 (베이스라인)${RST}"

BASELINE_PIDS=()
curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
    -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
BASELINE_PIDS+=($!)
for sid in "${SESSION_IDS[@]}"; do
    curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
        -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
    BASELINE_PIDS+=($!)
done
ALL_PIDS+=("${BASELINE_PIDS[@]}")
sleep 0.5

echo -e "  연결 후: $(get_status)  (기대: emitter=4)"
echo ""

# ── STEP 2: 4개 eventKey × PARALLEL개 요청 전부 동시 발사 ───
echo -e "${YLW}[STEP 2] Race Condition 유발 — 4개 eventKey × ${PARALLEL}개 동시 요청 × ${REPEAT}회${RST}"
echo ""

PREV_PIDS=("${BASELINE_PIDS[@]}")

for i in $(seq 1 "${REPEAT}"); do
    ROUND_PIDS=()

    # 이전 연결 종료
    cleanup_pids "${PREV_PIDS[@]}"

    # 핵심: $() 명령 치환 없이 직접 & 로 발사
    # $()를 사용하면 서브셸 종료까지 블로킹되어 eventKey 간 순차 실행이 되므로 사용 금지
    # 4개 eventKey × PARALLEL개 = 전체 요청이 진짜 동시에 서버에 도달
    for _ in $(seq 1 "${PARALLEL}"); do
        curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}" \
            -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
        ROUND_PIDS+=($!)
    done
    for sid in "${SESSION_IDS[@]}"; do
        for _ in $(seq 1 "${PARALLEL}"); do
            curl -s -N "${HOST}/api/v1/sse/subscribe?conferenceId=${CONF}&sessionId=${sid}" \
                -H "Accept: text/event-stream" --no-buffer > /dev/null 2>&1 &
            ROUND_PIDS+=($!)
        done
    done

    ALL_PIDS+=("${ROUND_PIDS[@]}")

    # race window 관찰을 위한 최소 대기
    sleep 0.05

    CURRENT=$(get_emitter_count)
    CURRENT=${CURRENT:-0}

    # emitter > 4 = 동일 key에 중복 등록된 순간 포착
    if [ "${CURRENT}" -gt 4 ] 2>/dev/null; then
        RACE_COUNT=$((RACE_COUNT + 1))
        echo -e "  [Round ${i}/${REPEAT}] ${RED}emitter=${CURRENT} — RACE CONDITION! (중복 등록)${RST}"
        LEAK_DETECTED=1
    fi

    if [ "${CURRENT}" -gt "${MAX_EMITTER}" ] 2>/dev/null; then
        MAX_EMITTER="${CURRENT}"
    fi

    # ROUND_PIDS 구조: [conf×PARALLEL | sid1×PARALLEL | sid2×PARALLEL | sid3×PARALLEL]
    # eventKey당 첫 번째 PID만 다음 라운드의 이전 연결로 유지, 나머지 종료
    PREV_PIDS=()
    for key_idx in 0 1 2 3; do
        offset=$((key_idx * PARALLEL))
        PREV_PIDS+=("${ROUND_PIDS[${offset}]}")
        for dup in $(seq 1 $((PARALLEL - 1))); do
            kill "${ROUND_PIDS[$((offset + dup))]}" 2>/dev/null
        done
    done
done

sleep 0.5
HEAP_END=$(heap_mb)

# 마지막 라운드 survivor 명시적 종료 후 서버가 emitter를 정리할 시간 확보
cleanup_pids "${PREV_PIDS[@]}"
sleep 0.5

# ── STEP 3: 최종 상태 ────────────────────────────────────────
echo ""
echo -e "${YLW}[STEP 3] 최종 상태${RST}"
STATUS=$(curl -s "${HOST}/api/v1/sse/status" 2>/dev/null)
echo "$STATUS" | python3 -m json.tool 2>/dev/null || echo "$STATUS"

FINAL_E=$(echo "$STATUS" | grep -o '"emitterCount":[0-9]*' | grep -o '[0-9]*')

cleanup_pids "${ALL_PIDS[@]}"

# ── 결과 요약 ────────────────────────────────────────────────
echo ""
echo "========================================================"
echo " 결과 요약"
echo ""
echo "  반복 횟수              : ${REPEAT}회"
echo "  동시 요청 수 (per key) : ${PARALLEL}개"
echo "  Race Condition 발생    : ${RACE_COUNT}회  (emitter > 4 관찰)"
echo "  관찰 최대 emitter      : ${MAX_EMITTER}  (4 초과 시 중복 등록)"
echo "  최종 emitterCount      : ${FINAL_E:-?}"
echo "  힙 변화                : ${HEAP_START}MB → ${HEAP_END}MB"
echo ""

if [ "${LEAK_DETECTED}" -eq 1 ]; then
    echo -e "  ${RED}[BUG-1] Race Condition 확인: emitter 중복 등록 발생${RST}"
    echo "   → findEmitter()~save() 구간이 atomic하지 않음"
    echo "   → 수정: ConcurrentHashMap.compute() 또는 synchronized 블록으로 원자적 교체"
fi

HEAP_INCREASED=$(awk "BEGIN {print (\"${HEAP_END}\" != \"?\" && \"${HEAP_START}\" != \"?\" && ${HEAP_END} > ${HEAP_START} + 5) ? 1 : 0}" 2>/dev/null)
if [ "${HEAP_INCREASED}" = "1" ]; then
    echo -e "  ${RED}[BUG-2] 힙 증가 감지: ${HEAP_START}MB → ${HEAP_END}MB${RST}"
    echo "   → 덮어써진 emitter가 GC 수거 전 힙에 잔류 중"
fi

if [ "${LEAK_DETECTED}" -eq 0 ] && [ "${HEAP_INCREASED}" != "1" ]; then
    echo -e "  ${GRN}[PASS] Race Condition 미발생${RST}"
    echo "   재현율을 높이려면: bash scripts/reproduce/race_condition.sh 50 3"
fi

echo ""
echo " 판정 기준 설명:"
echo "   emitter > 4  : 동일 key에 두 emitter가 동시에 Map에 등록된 순간 (포착 어려움)"
echo "   힙 증가      : 덮어써진 emitter 객체가 참조 없이 힙에 잔류 (주 판정 기준)"
echo "   서버 로그    : IllegalStateException — complete()가 이미 종료된 emitter 호출"
echo "========================================================"
