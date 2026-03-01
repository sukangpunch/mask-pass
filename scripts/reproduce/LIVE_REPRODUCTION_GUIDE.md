# 실서버 버그 재연 가이드

> 실제 서버를 기동한 뒤 스크립트로 버그를 재연하고, 수치로 확인한다.

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

### 2. 서버 기동

```bash
./gradlew bootRun --args='--spring.profiles.active=local'

# 기동 확인
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

### 3. 모니터 실행 (별도 터미널)

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

## Bug-1. 좀비 소켓 — 서버 재연 절차

**현상:** 클라이언트가 SIGKILL(탭 강제 닫기, 네트워크 단절)로 종료하면
서버의 `onError`/`onCompletion`이 발화하지 않아 emitter가 Map에 잔류한다.

### 재연

```bash
# 터미널 C
bash scripts/reproduce/zombie_socket.sh 5
```

### 수정 전 기대 출력

```
[STEP 2] 연결 후 emitter 상태
{
  "emitterCount": 5,
  "lastKnownCountsSize": 5,
  ...
}

[STEP 5] SIGKILL 후 emitter 상태
{
  "emitterCount": 5,       ← 정리 안 됨!
  "lastKnownCountsSize": 5
}

[BUG 확인] emitter 5개 잔류 — 좀비 소켓 발생!
```

### 모니터 터미널(B) 동시 관찰

```
[14:32:10]
  SSE Emitter 수        : 5
[14:32:15]  ← SIGKILL 5초 후
  SSE Emitter 수        : 5   ← 변화 없음이 버그
```

### 수정 후 기대 출력 (Heartbeat 도입 후)

```
# 서버 로그
Heartbeat 실패, 좀비 emitter 제거: key=conference:1
Heartbeat 실패, 좀비 emitter 제거: key=conference:2
...

# /api/v1/sse/status
{
  "emitterCount": 0       ← 정리됨
}
```

---

## Bug-2. Race Condition — 프론트 새로고침 재연

**현상:** 동일 conferenceId로 거의 동시에 subscribe 요청이 오면
기존 emitter가 `complete()` 안 된 채로 교체될 수 있다.

### 재연

```bash
bash scripts/reproduce/race_condition.sh
```

### 수정 전 기대 출력

```
[STEP 2] 재연결 완료 후 상태
{
  "emitterCount": 2,   ← race 발생 시 2개 이상 잔류
  ...
}
[BUG 확인] emitter 2개 — race condition 발생!
```

> **재현율 주의:** race condition은 CPU 속도에 따라 항상 발생하지 않는다.
> `sleep 0.1`을 줄이거나 반복 횟수를 늘리면 재현율 상승.

---

## Bug-3. lastKnownCounts 메모리 누수 재연

**현상:** emitter가 onCompletion/onTimeout/onError로 Map에서 제거되어도
`lastKnownCounts` Map에는 해당 키가 영구 잔류한다.

### 재연

```bash
bash scripts/reproduce/memory_leak.sh 30
```

### 수정 전 기대 출력

```
[10/30] lastKnownCounts=10, emitter=0, heap=145.2MB
[20/30] lastKnownCounts=20, emitter=0, heap=146.1MB
[30/30] lastKnownCounts=30, emitter=0, heap=147.0MB

최종 상태
  emitterCount    : 0   (기대: 0) ← 이건 정상
  lastKnownCounts : 30  (기대: 0) ← 누수!

[BUG 확인] lastKnownCounts에 30개 키 잔류 — 메모리 누수!
```

### 수정 후 기대 출력

```
[10/30] lastKnownCounts=0, emitter=0, heap=143.5MB
[30/30] lastKnownCounts=0, emitter=0, heap=143.8MB

[PASS] lastKnownCounts 정상 정리됨
```

---

## Bug-4. OOM 경향 — 대량 연결 누적 시뮬레이션

**현상:** 좀비 소켓 + 메모리 누수가 복합으로 작용하면 장기 운영 시 Heap이 점진적으로 증가한다.

### 재연

```bash
# 20개 연결 × 3라운드
bash scripts/reproduce/oom_simulation.sh 20 3
```

### 수정 전 기대 출력

```
━━━ Round 1/3 : 20개 연결 생성 ━━━
  연결 후  : emitter=20 | lastKnown=20 | heap=148.2MB
  정리 후  : emitter=20 | lastKnown=20 | heap=149.1MB  ← 정리 안 됨

━━━ Round 2/3 : 20개 연결 생성 ━━━
  연결 후  : emitter=40 | lastKnown=40 | heap=151.3MB  ← 누적!
  정리 후  : emitter=40 | lastKnown=40 | heap=152.8MB

━━━ Round 3/3 : 20개 연결 생성 ━━━
  연결 후  : emitter=60 | lastKnown=60 | heap=155.0MB
  정리 후  : emitter=60 | lastKnown=60 | heap=156.2MB

최종 결과: emitter=60 잔류 (OOM 경향 확인)
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

### Micrometer SSE 게이지 (코드 추가 후)

```bash
# sse.emitter.count 커스텀 게이지 확인
curl -s "http://localhost:8080/actuator/metrics/sse.emitter.count" | python3 -m json.tool
```

### Prometheus 포맷 (Grafana 연동 시)

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
| 좀비 소켓 | `zombie_socket.sh 5` | emitter 5개 잔류 | emitter 0 | - |
| Race Condition | `race_condition.sh` | emitter ≥2 | emitter 1 | - |
| lastKnownCounts 누수 | `memory_leak.sh 30` | lastKnown 30 잔류 | lastKnown 0 | - |
| OOM 경향 | `oom_simulation.sh 20 3` | 60개 누적 | 0 누적 | - |

---

## 스크립트 실행 권한 설정

```bash
chmod +x scripts/reproduce/*.sh
```

## Windows(Git Bash / WSL) 환경 참고

```bash
# Git Bash에서 실행 시
bash scripts/reproduce/zombie_socket.sh 5

# bc 없을 경우 monitor.sh의 MB 계산이 안 될 수 있음
# → WSL 또는 python3로 대체 가능
python3 -c "print(f'{149000000 / 1048576:.1f} MB')"
```
