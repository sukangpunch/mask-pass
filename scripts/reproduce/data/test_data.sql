-- ============================================================
-- Mask-Pass SSE 버그 재연용 테스트 데이터
--
-- 목표:
--   conference_id=1 (세션 3개 포함)로 4개의 SSE 스트림 구독 테스트
--   - conference:1
--   - conference:1:session:1
--   - conference:1:session:2
--   - conference:1:session:3
--
-- 실행:
--   bash scripts/reproduce/data/setup.sh
--   또는 직접: psql -U postgres -d mask_pass_db -f scripts/reproduce/data/test_data.sql
--
-- 주의: 기존 테스트 데이터를 덮어씁니다 (ON CONFLICT DO UPDATE 사용)
-- ============================================================

-- ────────────────────────────────────────────────────────────
-- 1. Conference (id=1)
-- ────────────────────────────────────────────────────────────
INSERT INTO conference (
    conference_id,
    name,
    description,
    location,
    start_time,
    end_time,
    capacity,
    image_key,
    is_active,
    has_sessions
)
OVERRIDING SYSTEM VALUE
VALUES (
    1,
    '구름 개발자 컨퍼런스 2026',
    'SSE 좀비 소켓 & OOM 재연용 테스트 컨퍼런스',
    '서울특별시 강남구 테헤란로 123, 구름타워 대강당',
    '2026-06-01 09:00:00',
    '2026-06-01 18:00:00',
    500,
    'https://example.com/images/conference-banner.jpg',
    true,
    true
)
ON CONFLICT (conference_id) DO UPDATE
    SET name        = EXCLUDED.name,
        description = EXCLUDED.description,
        is_active   = EXCLUDED.is_active,
        has_sessions= EXCLUDED.has_sessions;

-- 시퀀스 동기화 (다음 INSERT가 2부터 시작하도록)
SELECT setval(pg_get_serial_sequence('conference', 'conference_id'), (SELECT MAX(conference_id) FROM conference));

-- ────────────────────────────────────────────────────────────
-- 2. Sessions (conference_id=1, id=1,2,3)
-- ────────────────────────────────────────────────────────────
INSERT INTO session (
    session_id,
    conference_id,
    name,
    capacity,
    location,
    start_time,
    end_time,
    summary,
    speaker_name,
    speaker_organization,
    is_active,
    speaker_image_key
)
OVERRIDING SYSTEM VALUE
VALUES
(
    1,
    1,
    '세션 1: Spring Event-Driven Architecture',
    100,
    '101호 (세미나실 A)',
    '2026-06-01 10:00:00',
    '2026-06-01 11:30:00',
    'Spring ApplicationEvent를 활용한 느슨한 결합 아키텍처 설계',
    '김스프링',
    '구름',
    true,
    null
),
(
    2,
    1,
    '세션 2: Redis 실무 활용 — 캐시·분산락·Pub/Sub',
    80,
    '102호 (세미나실 B)',
    '2026-06-01 13:00:00',
    '2026-06-01 14:30:00',
    'Redis를 활용한 캐싱 전략 및 분산 환경에서의 동시성 제어',
    '이레디스',
    '구름',
    true,
    null
),
(
    3,
    1,
    '세션 3: SSE vs WebSocket — 실시간 통신 선택 기준',
    60,
    '103호 (세미나실 C)',
    '2026-06-01 15:00:00',
    '2026-06-01 16:30:00',
    'Server-Sent Events와 WebSocket의 트레이드오프 분석과 실전 선택 기준',
    '박실시간',
    '구름',
    true,
    null
)
ON CONFLICT (session_id) DO UPDATE
    SET name                 = EXCLUDED.name,
        location             = EXCLUDED.location,
        is_active            = EXCLUDED.is_active,
        speaker_name         = EXCLUDED.speaker_name,
        speaker_organization = EXCLUDED.speaker_organization;

SELECT setval(pg_get_serial_sequence('session', 'session_id'), (SELECT MAX(session_id) FROM session));

-- ────────────────────────────────────────────────────────────
-- 3. Users (테스트용 — SSE 테스트에는 불필요, 얼굴인증 테스트 시 활용)
--    password 평문: "Test1234!"
--    BCrypt hash (cost=10): $2a$10$rrWnhFxMSFQi1qlpXVBEYO6XrEMC7xVaB4Ld7QHWBuKxSfFb6kbfu
-- ────────────────────────────────────────────────────────────
INSERT INTO users (
    user_id,
    email,
    name,
    password,
    phone,
    is_deleted,
    role,
    has_face,
    created_at,
    updated_at
)
OVERRIDING SYSTEM VALUE
VALUES
(
    1,
    'admin@test-goorm.com',
    '관리자',
    '$2a$10$rrWnhFxMSFQi1qlpXVBEYO6XrEMC7xVaB4Ld7QHWBuKxSfFb6kbfu',
    '010-0000-0001',
    false,
    'ADMIN',
    false,
    NOW(),
    NOW()
),
(
    2,
    'user1@test-goorm.com',
    '참석자1',
    '$2a$10$rrWnhFxMSFQi1qlpXVBEYO6XrEMC7xVaB4Ld7QHWBuKxSfFb6kbfu',
    '010-1111-0001',
    false,
    'USER',
    false,
    NOW(),
    NOW()
),
(
    3,
    'user2@test-goorm.com',
    '참석자2',
    '$2a$10$rrWnhFxMSFQi1qlpXVBEYO6XrEMC7xVaB4Ld7QHWBuKxSfFb6kbfu',
    '010-1111-0002',
    false,
    'USER',
    false,
    NOW(),
    NOW()
),
(
    4,
    'user3@test-goorm.com',
    '참석자3',
    '$2a$10$rrWnhFxMSFQi1qlpXVBEYO6XrEMC7xVaB4Ld7QHWBuKxSfFb6kbfu',
    '010-1111-0003',
    false,
    'USER',
    false,
    NOW(),
    NOW()
)
ON CONFLICT (user_id) DO UPDATE
    SET email      = EXCLUDED.email,
        name       = EXCLUDED.name,
        is_deleted = false;

SELECT setval(pg_get_serial_sequence('users', 'user_id'), (SELECT MAX(user_id) FROM users));

-- ────────────────────────────────────────────────────────────
-- 확인 쿼리
-- ────────────────────────────────────────────────────────────
SELECT 'conference' AS tbl, COUNT(*) AS cnt FROM conference WHERE conference_id = 1
UNION ALL
SELECT 'session',  COUNT(*) FROM session WHERE conference_id = 1
UNION ALL
SELECT 'users',    COUNT(*) FROM users WHERE email LIKE '%@test-goorm.com';
