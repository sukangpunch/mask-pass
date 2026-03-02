#!/bin/bash
# ============================================================
# setup.sh — 테스트 데이터 로드 (Docker PostgreSQL)
#
# 사용법: bash scripts/reproduce/data/setup.sh
# 사전 조건: docker-compose -f docker-compose.local.yml up -d
# ============================================================

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/test_data.sql"

# Docker 컨테이너명 (docker-compose.local.yml 기준)
PG_CONTAINER="mask-pass-postgres-local"
PG_USER="postgres"
PG_DB="mask_pass_db"

RED='\033[0;31m'
GRN='\033[0;32m'
YLW='\033[1;33m'
RST='\033[0m'

echo "========================================================"
echo " Mask-Pass 테스트 데이터 로드"
echo " SQL: ${SQL_FILE}"
echo " DB : ${PG_CONTAINER} / ${PG_DB}"
echo "========================================================"

# ── 컨테이너 실행 여부 확인 ──────────────────────────────────
if ! docker ps --format '{{.Names}}' | grep -q "^${PG_CONTAINER}$"; then
    echo -e "${RED}[ERROR] PostgreSQL 컨테이너가 실행 중이 아닙니다.${RST}"
    echo "  → docker-compose -f docker-compose.local.yml up -d"
    exit 1
fi

# ── 서버 기동 여부 확인 (DDL auto-create가 완료됐는지) ──────────
echo ""
echo -e "${YLW}[1/3] 테이블 존재 여부 확인...${RST}"
TABLE_CHECK=$(docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -tAc \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'conference';" 2>/dev/null)

if [ "${TABLE_CHECK:-0}" -eq 0 ]; then
    echo -e "${RED}[ERROR] conference 테이블이 없습니다.${RST}"
    echo "  → Spring Boot 서버를 먼저 기동해서 JPA DDL auto-create를 완료하세요."
    echo "  → ./gradlew bootRun --args='--spring.profiles.active=local'"
    exit 1
fi
echo -e "  ${GRN}테이블 확인 완료${RST}"

# ── SQL 실행 ─────────────────────────────────────────────────
echo ""
echo -e "${YLW}[2/3] SQL 실행 중...${RST}"
docker exec -i "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" < "$SQL_FILE"
EXIT_CODE=$?

if [ $EXIT_CODE -ne 0 ]; then
    echo -e "${RED}[ERROR] SQL 실행 실패 (exit code: ${EXIT_CODE})${RST}"
    exit $EXIT_CODE
fi

# ── 결과 확인 ────────────────────────────────────────────────
echo ""
echo -e "${YLW}[3/3] 데이터 확인${RST}"
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c \
    "SELECT conference_id, name, has_sessions FROM conference WHERE conference_id = 1;"
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -c \
    "SELECT session_id, conference_id, name, location FROM session WHERE conference_id = 1 ORDER BY session_id;"

echo ""
echo -e "${GRN}========================================================"
echo " 테스트 데이터 로드 완료!"
echo ""
echo " SSE 구독 대상:"
echo "   conference:1           → /api/v1/sse/subscribe?conferenceId=1"
echo "   conference:1:session:1 → /api/v1/sse/subscribe?conferenceId=1&sessionId=1"
echo "   conference:1:session:2 → /api/v1/sse/subscribe?conferenceId=1&sessionId=2"
echo "   conference:1:session:3 → /api/v1/sse/subscribe?conferenceId=1&sessionId=3"
echo -e "========================================================${RST}"
