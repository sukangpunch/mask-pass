# 실서버 버그 재연 가이드

> 실제 서버를 기동한 뒤 스크립트로 버그를 재연하고, 수치로 확인한다.
>
> **테스트 시나리오:** conference:1 + session:1,2,3 (총 4개 SSE 스트림)
> 태블릿 1대가 컨퍼런스를 모니터링하는 상황 — 새로고침(탭 강제 닫기 + 재연결) 반복

---

## 사전 준비

### 1. 인프라 기동

```bash
# Docker: Redis + PostgreSQL
docker-compose -f docker-compose.local.yml up -d

# 기동 확인
docker ps
# mask-pass-redis-local    ← redis:6379
# mask-pass-postgres-local ← postgres:5432
```

### 2. 서버 기동 (DDL auto 필요)

```bash
./gradlew bootRun --args='--spring.profiles.active=local'

# 기동 확인
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 3. 테스트 데이터 삽입

서버를 먼저 기동해 JPA DDL로 테이블을 생성한 뒤 SQL을 실행한다.

```bash
# PostgreSQL 컨테이너 이름 확인 후 실행
bash scripts/reproduce/data/setup.sh

# 또는 직접 삽입
docker exec -i <postgres-container-name> psql -U <user> -d <db> \
  < scripts/reproduce/data/test_data.sql
```

삽입 내용:
- Conference id=1 ('구름 개발자 컨퍼런스 2026', hasSessions=true, isActive=true)
- Session id=1,2,3 (conference_id=1, capacity=200, isActive=true)
- User id=1 (admin) + id=2,3,4 (test users, 비밀번호: Test1234!)

### 4. 모니터 실행 (별도 터미널)

```bash
# 터미널 B (항상 열어두고 관찰)
bash scripts/reproduce/monitor.sh 3
```

출력 예시:
```
[14:32:01]
  SSE Emitter 수        : 0  (Micrometer: 0)
  lastKnownCounts 크기  : 0  ← emitter 종료 후에도 남으면 메모리 누수
  JVM Heap Used         : 142.3 MB
  Redis DBSIZE          : 0 keys
```

---

## 추천 실행 순서

```
zombie_socket.sh      → 좀비 소켓 기본 확인
  ↓
refresh_simulation.sh → 새로고침 반복 시뮬레이션 (메인 시나리오)
  ↓
race_condition.sh     → 동시 재연결 Race Condition 확인
  ↓
memory_leak.sh        → lastKnownCounts 누수 정량 확인
  ↓
oom_simulation.sh     → 장기 OOM 경향 종합 확인
```

---

## Bug-1. 좀비 소켓 — 기본 확인

**현상:** 클라이언트가 SIGKILL(탭 강제 닫기, 네트워크 단절)로 종료하면
서버의 `onError`/`onCompletion`이 발화하지 않아 emitter가 Map에 잔류한다.

**구독 대상:** conference:1 + session:1,2,3 (총 4개)

### 재연

```bash
# 터미널 C (SIGKILL 후 7초간 관찰)
bash scripts/reproduce/zombie_socket.sh 7
```

### 수정 전 기대 출력

```
[STEP 2] 4개 SSE 연결 시작
  [연결] conference:1             PID=12345
  [연결] conference:1:session:1   PID=12346
  [연결] conference:1:session:2   PID=12347
  [연결] conference:1:session:3   PID=12348

[STEP 2] 연결 후 상태 (기대: emitter=4)
  emitter=4개 | lastKnownCounts=4개

[STEP 3] SIGKILL — 4개 연결 강제 종료

[STEP 4] SIGKILL 직후 상태 (기대: emitter=4 zombie!)
  emitter=4개 | lastKnownCounts=4개
  ↑ emitter가 4로 남아있으면 좀비 소켓 발생

  7초간 관찰 중...
  [1s 후] emitter=4
  [2s 후] emitter=4
  ...
  [7s 후] emitter=4   ← 서버가 감지 못함

[결과] emitter=4  (버그: 4개 잔류)
[BUG] emitter 4개 잔류 — 좀비 소켓 발생!
```

### 수정 후 기대 출력 (Heartbeat 도입 후)

```
[1s 후] emitter=4
[2s 후] emitter=0   ← heartbeat이 CLOSE_WAIT 감지, 즉시 정리

[PASS] emitter 정상 정리됨 (onError/onCompletion 발화)
```

---

## Bug-2. 브라우저 새로고침 시뮬레이션 (메인 시나리오)

**현상:** 새로고침 = "이전 탭 강제 종료(SIGKILL) → 즉시 재연결" 반복.
SIGKILL과 재연결 사이 zombie 상태가 일정 시간 지속되며 Tomcat 스레드·소켓을 점유한다.

**구독 대상:** conference:1 + session:1,2,3 (총 4개)
**각 사이클:** 4개 연결 → SIGKILL → N초 zombie → (반복)

### 재연

```bash
# 5회 새로고침, SIGKILL 후 3초 관찰
bash scripts/reproduce/refresh_simulation.sh 5 3

# 10회, zombie 5초 유지
bash scripts/reproduce/refresh_simulation.sh 10 5
```

### 수정 전 기대 출력

```
================================================================
 브라우저 새로고침 시뮬레이션
 대상 : conference:1 + session:1,2,3 (4개 스트림)
 사이클: 4개 연결 → SIGKILL → 3초 zombie → 재연결 (5회)
================================================================

[초기] emitter=0  | lastKnown=0  | heap=142.1MB

━━━ Round 1/5 ━━━
  [연결 완료] emitter=4  | lastKnown=4  | heap=143.2MB
  [SIGKILL  ] emitter=4  | lastKnown=4  | heap=143.2MB  ← zombie 시작
  [3초 후   ] emitter=4  | lastKnown=4  | heap=143.5MB  ← zombie 지속

━━━ Round 2/5 ━━━
  [연결 완료] emitter=4  | lastKnown=4  | heap=143.8MB  ← 재연결 시 교체됨
  [SIGKILL  ] emitter=4  | lastKnown=4  | heap=143.9MB
  [3초 후   ] emitter=4  | lastKnown=4  | heap=144.0MB

... (5회 반복)

[최종 상태] emitter=4 | lastKnown=4 | heap=144.5MB

[BUG] 4개 zombie 잔류!
   → SIGKILL ~ 재연결 사이 3초 동안 Tomcat 스레드/소켓 점유
   → 재연결 없이 방치 시 1800초(timeout)까지 자원 점유 지속
```

### 수정 후 기대 출력 (Heartbeat 도입 후)

```
━━━ Round 1/5 ━━━
  [연결 완료] emitter=4  | lastKnown=4  | heap=143.2MB
  [SIGKILL  ] emitter=4  | lastKnown=4  | heap=143.2MB
  [3초 후   ] emitter=0  | lastKnown=0  | heap=143.1MB  ← heartbeat 감지·정리

[PASS] 마지막 SIGKILL 후 emitter 정리됨
```

---

## Bug-3. Race Condition — 동시 재연결

**현상:** 동일 eventKey로 거의 동시에 두 subscribe 요청이 오면
기존 emitter가 `complete()` 안 된 채로 덮어써지거나 중복 등록된다.

```
Thread A: findEmitter("conference:1") → null
Thread B: findEmitter("conference:1") → null   (A가 save하기 전)
Thread A: save("conference:1", emitterA)
Thread B: save("conference:1", emitterB)        → emitterA 누수!
```

**구독 대상:** conference:1 + session:1,2,3 (총 4개 — 모두 동시 재연결)

### 재연

```bash
# 4개 스트림 동시 재연결 20회 반복
bash scripts/reproduce/race_condition.sh 20

# 더 높은 재현율 (서버 부하 동반)
bash scripts/reproduce/race_condition.sh 50
```

### 수정 전 기대 출력

```
[STEP 1] 초기 4개 SSE 연결
  연결 후: emitter=4 | lastKnownCounts=4  (기대: emitter=4)

[STEP 2] 동시 재연결 20회 반복 (race condition 유발)

  [Round 3] emitter=5 — RACE CONDITION 발생!
  [Round 7] emitter=6 — RACE CONDITION 발생!
  ...

[결과]
  최종 emitterCount : 5  (기대: 4, race 시: >4)
  관찰 최대 emitter : 7  (4 초과 시 race condition)

[BUG 확인] Race Condition 발생! emitter 누수 또는 중복 등록
```

> **재현율 주의:** CPU 속도에 따라 항상 발생하지 않는다.
> repeat 횟수를 늘리거나 서버에 부하를 주면 재현율 상승.

### 수정 후 기대 출력 (getAndReplace() 원자적 교체 후)

```
[PASS] emitter 4개 유지 — race condition 미발생
  (재현율이 낮을 수 있음 — repeat 횟수를 늘리거나 서버 부하 증가)
```

---

## Bug-4. lastKnownCounts 메모리 누수

**현상:** emitter가 onCompletion/onTimeout/onError로 emitter Map에서 제거되어도
`lastKnownCounts` Map에는 해당 키가 영구 잔류한다.

### 재연

```bash
# Phase 1: 4개 고정 스트림 (conf:1 + session:1,2,3) 구독 후 정상 종료
# Phase 2: 다른 conferenceId 30개 순환 → 누수 규모 확인
bash scripts/reproduce/memory_leak.sh 30
```

### 수정 전 기대 출력

```
[Phase 1] conference:1 + session:1,2,3 — 4개 구독 후 정상 종료
  초기:   emitter=0 | lastKnown=0
  연결 후: emitter=4 | lastKnown=4
  종료 후: emitter=0 | lastKnown=4  ← 4 남으면 버그!

[Phase 2] 다른 conferenceId 순환 구독 → 해제 (30회)

  [10/30] lastKnown=14 | emitter=0 | heap=145.2MB
  [20/30] lastKnown=24 | emitter=0 | heap=146.1MB
  [30/30] lastKnown=34 | emitter=0 | heap=147.0MB

[최종 상태]
  emitterCount    : 0   (기대: 0) ← 정상
  lastKnownCounts : 34  (기대: 0, 버그 시: 34) ← 누수!

[BUG] lastKnownCounts에 34개 잔류
   → emitter 종료 시 lastKnownCounts.remove() 누락
```

### 수정 후 기대 출력

```
[Phase 1]
  종료 후: emitter=0 | lastKnown=0  ← 정상 정리

[Phase 2]
  [10/30] lastKnown=0 | emitter=0 | heap=143.5MB
  [30/30] lastKnown=0 | emitter=0 | heap=143.8MB

[PASS] lastKnownCounts 정상 정리됨 (수정 후 동작)
```

---

## Bug-5. OOM 경향 — 장기 누적 시뮬레이션

**현상:** 좀비 소켓 + 메모리 누수가 복합으로 작용하면 장기 운영 시 Heap이 점진적으로 증가한다.

**Phase A:** conference:1 + session:1,2,3 (고정 4개) — SIGKILL 후 재연결 없이 방치
- 같은 key라 emitter Map은 최대 4 — TCP/소켓 자원 점유 관찰
**Phase B:** 다른 conferenceId 사용 — 실제 OOM 시나리오 (다수 컨퍼런스에서 태블릿 단절)
- 새로운 key마다 zombie가 누적 → 실제 OOM 원인

### 재연

```bash
# Phase A 5라운드 + Phase B 5라운드
bash scripts/reproduce/oom_simulation.sh 4 5

# 실제 OOM 경향 확인 (고유 conferenceId 10개 × 10라운드 = 10좀비 누적)
bash scripts/reproduce/oom_simulation.sh 4 10
```

### 수정 전 기대 출력

```
━━━ Phase A: 고정 4개 스트림 반복 SIGKILL ━━━

[Round A-1/5]
  연결 후 : emitter=4  | lastKnown=4  | heap=143.2MB
  SIGKILL : emitter=4  | lastKnown=4  | heap=143.5MB  ← zombie (재연결 없으므로 cleanup 안 됨)

[Round A-2/5]
  연결 후 : emitter=4  | lastKnown=4  | heap=143.8MB  (같은 key → 교체)
  SIGKILL : emitter=4  | lastKnown=4  | heap=144.0MB

━━━ Phase B: 다른 conferenceId 5개 누적 SIGKILL ━━━

[Round B-1/5] conferenceId=101
  연결 후 : emitter=5  | lastKnown=5  | heap=144.2MB
  SIGKILL : emitter=5  | lastKnown=5  | heap=144.5MB  ← emitter 누적 중

[Round B-2/5] conferenceId=102
  연결 후 : emitter=6  | lastKnown=6  | heap=144.8MB
  SIGKILL : emitter=6  | lastKnown=6  | heap=145.1MB

...

최종 결과 (Phase A + Phase B 후)
  emitterCount    : 9
  lastKnownCounts : 9
  heap            : 146.3MB

[OOM 경향] emitter=9 잔류 — 장기 운영 시 OOM 위험
```

---

## 보조 모니터링 명령어

### JVM Heap 상세

```bash
# Heap used (bytes)
curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:heap" | python3 -m json.tool

# Non-Heap (메타스페이스 등)
curl -s "http://localhost:8080/actuator/metrics/jvm.memory.used?tag=area:nonheap" | python3 -m json.tool

# GC 횟수
curl -s "http://localhost:8080/actuator/metrics/jvm.gc.pause" | python3 -m json.tool
```

### Micrometer SSE 게이지

```bash
# sse.emitter.count 커스텀 게이지 확인
curl -s "http://localhost:8080/actuator/metrics/sse.emitter.count" | python3 -m json.tool
```

### SSE 상태 엔드포인트

```bash
# emitter 수 + lastKnownCounts 크기 한번에 확인
curl -s "http://localhost:8080/api/v1/sse/status" | python3 -m json.tool
```

### Prometheus 포맷

```bash
curl -s "http://localhost:8080/actuator/prometheus" | grep sse
# sse_emitter_count 0.0
```

### Redis 상태

```bash
# Redis 전체 키 수
redis-cli DBSIZE

# 참석자 관련 키 목록
redis-cli KEYS "conference*"

# 특정 conference 카운트 확인
redis-cli GET "conference_count:1"

# 특정 conference 참석자 Set 확인
redis-cli SMEMBERS "conference:1"

# 실시간 명령어 모니터
redis-cli MONITOR
```

### 네트워크 소켓 상태 (CLOSE_WAIT 확인)

```bash
# CLOSE_WAIT 상태 소켓 수
ss -tnp | grep CLOSE_WAIT | wc -l

# 자세한 소켓 상태
ss -tnp | grep ':8080'
```

### 서버 스레드 상태

```bash
# 스레드 덤프 (Actuator)
curl -s "http://localhost:8080/actuator/threaddump" | python3 -m json.tool | grep -A5 "event-Async"
```

---

## 재연 결과 기록 표

| Bug | 스크립트 | 수정 전 결과 | 수정 후 결과 | 해결일 |
|-----|---------|-------------|-------------|--------|
| 좀비 소켓 | `zombie_socket.sh 7` | emitter 4개 잔류 | emitter 0 | - |
| 새로고침 zombie | `refresh_simulation.sh 5 3` | zombie N초 지속 | heartbeat 즉시 정리 | - |
| Race Condition | `race_condition.sh 20` | emitter >4 관찰 | emitter=4 유지 | - |
| lastKnownCounts 누수 | `memory_leak.sh 30` | lastKnown 34 잔류 | lastKnown 0 | - |
| OOM 경향 | `oom_simulation.sh 4 10` | emitter 누적 잔류 | 0 누적 | - |

---

## 스크립트 실행 권한 설정

```bash
chmod +x scripts/reproduce/*.sh
chmod +x scripts/reproduce/data/setup.sh
```

## Windows(Git Bash / WSL) 환경 참고

```bash
# Git Bash에서 실행 시
bash scripts/reproduce/zombie_socket.sh 7

# bc 없을 경우 monitor.sh의 MB 계산이 안 될 수 있음
# → WSL 또는 python3로 대체 가능
python3 -c "print(f'{149000000 / 1048576:.1f} MB')"
```
