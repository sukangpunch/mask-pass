# Mask-Pass 리팩토링 계획서

> 작성일: 2026-03-01
> 대상 브랜치: `dev`
> 우선순위 순으로 정렬

---

## 목차

1. [SSE 성능 개선 및 좀비 소켓 OOM 문제](#1-sse-성능-개선-및-좀비-소켓-oom-문제)
2. [비동기 스레드 풀 최적화](#2-비동기-스레드-풀-최적화)
3. [비동기 처리 보상 트랜잭션](#3-비동기-처리-보상-트랜잭션)
4. [기타 발견된 문제들](#4-기타-발견된-문제들)
5. [작업 순서 요약](#5-작업-순서-요약)

---

## 1. SSE 성능 개선 및 좀비 소켓 OOM 문제

### 1-A. `subscribe()` 내 Race Condition (가장 심각)

**파일:** `SseService.java`

**현재 코드 흐름:**
```java
// 1. 조회
SseEmitter emitter = emitterRepository.findEmitterByKey(eventKey);
// 2. 완료 처리
if (emitter != null) { emitter.complete(); }
// 3. 삭제
emitterRepository.deleteByEventKey(eventKey);
// 4. 저장 (새 emitter)
SseEmitter sseEmitter = emitterRepository.save(eventKey, new SseEmitter(TIMEOUT));
```

**문제:** 1~4번 사이에 다른 스레드가 끼어들면 `complete()`도 안 된 기존 emitter가 Map에 남아 좀비가 됨.
특히 프론트가 새로고침 시 빠르게 재연결하면 이 타이밍이 실제로 발생함.

**해결 방향:**
- `SseEmitterRepository`의 `emitters` Map에서 `ConcurrentHashMap.compute()` 또는 `merge()`를 사용해 조회-삭제-저장을 **단일 원자 연산**으로 처리.

```java
// SseEmitterRepository에 원자적 교체 메서드 추가
public SseEmitter replaceEmitter(String eventKey, SseEmitter newEmitter) {
    // compute 안에서 기존 emitter complete() 후 newEmitter 반환
    emitters.compute(eventKey, (key, existing) -> {
        if (existing != null) {
            try { existing.complete(); } catch (Exception ignored) {}
        }
        return newEmitter;
    });
    return newEmitter;
}
```

**수정 파일:**
- `SseEmitterRepository.java` — `replaceEmitter()` 메서드 추가
- `EmitterRepository.java` (인터페이스) — 메서드 선언 추가
- `SseService.java` — `subscribe()` 내 로직을 `replaceEmitter()` 호출로 교체

---

### 1-B. `lastKnownCounts` 메모리 누수

**파일:** `SseService.java`

**현재 코드:**
```java
private final Map<String, Long> lastKnownCounts = new ConcurrentHashMap<>();
```

**문제:**
- emitter가 `onCompletion` / `onTimeout` / `onError`로 제거되어도 `lastKnownCounts`에는 해당 key가 **영구적으로 남음**.
- 컨퍼런스가 끝나도 key가 쌓여서 장기 운영 시 지속적으로 메모리 증가.

**해결 방향:**
- `registerEmitterHandler()` 내 `onCompletion`, `onTimeout`, `onError` 콜백에 `lastKnownCounts.remove(eventId)` 추가.
- 또는 `unsubscribe()` 호출 시에도 같이 제거.

```java
sseEmitter.onCompletion(() -> {
    emitterRepository.deleteByEventKey(eventId);
    lastKnownCounts.remove(eventId);   // 추가
    log.info("연결이 끝났습니다. : eventId = {}", eventId);
});
```

**수정 파일:**
- `SseService.java` — `registerEmitterHandler()`, `unsubscribe()` 수정

---

### 1-C. 좀비 소켓 능동적 감지 (Heartbeat)

**파일:** `SseService.java`, `AsyncConfig.java` (또는 별도 스케줄러)

**문제:**
- 현재 `subscribe()` 진입 시에만 기존 연결 상태 확인 (`send("ping")`).
- Tomcat의 CLOSE_WAIT 상태 연결은 `onError`가 발화되지 않아 Map에 죽은 emitter가 남음.
- 다음 `subscribe()` 호출이 없으면 영구히 남아 OOM으로 이어짐.

**해결 방향:**

`@Scheduled`로 주기적 ping을 전송하고, 실패 시 emitter를 제거하는 heartbeat 메서드 추가.

```java
// SseService.java에 추가
@Scheduled(fixedDelay = 30_000)   // 30초마다
public void sendHeartbeat() {
    emitterRepository.getAllEmitters().forEach((key, emitter) -> {
        try {
            emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
        } catch (IOException e) {
            log.warn("Heartbeat 실패, 좀비 emitter 제거: key={}", key);
            emitter.complete();
            emitterRepository.deleteByEventKey(key);
            lastKnownCounts.remove(key);
        }
    });
}
```

- `SseEmitterRepository`에 `getAllEmitters()` 메서드 추가 (Map의 entrySet 반환).
- `@EnableScheduling`을 `AsyncConfig` 또는 별도 `SchedulingConfig`에 추가.

**수정 파일:**
- `SseEmitterRepository.java` — `getAllEmitters()` 추가
- `EmitterRepository.java` — 인터페이스에 `getAllEmitters()` 선언
- `SseService.java` — `sendHeartbeat()` 추가, `@Scheduled` 적용
- `AsyncConfig.java` 또는 `SchedulingConfig.java` — `@EnableScheduling` 추가

---

### 1-D. `sendAttendanceCount()`의 IOException 처리 오류

**파일:** `SseService.java`

**현재 코드:**
```java
} catch (IOException e) {
    throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);  // 문제!
}
```

**문제:**
- `sendAttendanceCount()`는 `@Async` 스레드(`AttendEventHandler`)에서 호출됨.
- `CustomException`을 던져도 `@Async` 특성상 호출부로 전파되지 않고 **조용히 소멸**.
- 하지만 이 예외가 `AttendEventHandler.handle()` 전체를 중단시켜 **이후 `registerAttend()`(DB 저장)가 실행되지 않음** → Redis와 DB 불일치.

**해결 방향:**
- `sendAttendanceCount()` 내에서 예외를 throw하지 말고, IOException 발생 시 해당 emitter를 **제거하고 로그만 남기도록** 변경.

```java
} catch (IOException e) {
    log.error("SSE 전송 실패, emitter 제거: eventId={}", eventKey);
    emitter.complete();
    emitterRepository.deleteByEventKey(eventKey);
    lastKnownCounts.remove(eventKey);
    // 예외 re-throw 하지 않음 → 이후 registerAttend() 정상 실행
}
```

**수정 파일:**
- `SseService.java` — `sendAttendanceCount()` catch 블록 수정

---

## 2. 비동기 스레드 풀 최적화

### 2-A. `AsyncUncaughtExceptionHandler` 미구현

**파일:** `AsyncConfig.java`

**문제:**
- `@Async` 메서드에서 발생한 예외는 `SimpleAsyncUncaughtExceptionHandler`가 처리하는데, 이는 단순 로그만 남기고 끝.
- 현재 `AttendEventHandler.handle()`에서 예외가 나면 **아무 알림 없이 소멸**.

**해결 방향:**
- `AsyncConfigurer`를 구현하여 커스텀 `AsyncUncaughtExceptionHandler` 등록.
- Discord 웹훅 또는 알림 로그 전송으로 운영 시 즉시 감지.

```java
// AsyncConfig.java 수정
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            log.error("[ASYNC ERROR] method={}, params={}, error={}",
                      method.getName(), Arrays.toString(params), ex.getMessage(), ex);
            // 필요 시 Discord 알림 또는 메트릭 카운터 증가
        };
    }
}
```

**수정 파일:**
- `AsyncConfig.java` — `AsyncConfigurer` 구현, `getAsyncUncaughtExceptionHandler()` 재정의

---

### 2-B. `RejectedExecutionHandler` 미설정

**파일:** `AsyncConfig.java`

**문제:**
- 현재 queueCapacity=50인데, 동시 입장자 폭증 시 큐가 포화되면 Spring의 기본 `AbortPolicy`가 적용되어 `RejectedExecutionException` 발생.
- 이 예외도 `@Async` 특성상 전파되지 않아 입장 처리 자체가 **무음 실패**.

**해결 방향:**
- `CallerRunsPolicy` 적용 (요청 스레드가 직접 실행) — 응답 지연은 생기지만 유실 없음.
- 또는 queueCapacity를 500 등으로 상향하고 모니터링 메트릭으로 포화 감지.

```java
executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
```

**수정 파일:**
- `AsyncConfig.java`

---

### 2-C. Virtual Thread 도입 (Java 21)

**파일:** `AsyncConfig.java`

**배경:**
- 현재 비동기 작업(Redis I/O, PostgreSQL I/O, SSE 전송)은 모두 I/O bound.
- Java 21 Virtual Thread는 I/O 대기 중 캐리어 스레드를 양보하므로 플랫폼 스레드 수백 개 없이도 수천 개 동시 I/O 처리 가능.
- EC2 프리티어 1 vCPU 환경에서 컨텍스트 스위칭 비용 대비 처리량이 개선될 가능성 높음.

**해결 방향 (두 가지 옵션):**

**옵션 A: customTaskExecutor를 Virtual Thread 기반으로 교체**
```java
@Bean(name = "customTaskExecutor")
public Executor customTaskExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
}
```
- 장점: 스레드 수 제한 없음, I/O 대기 중 블로킹 없음
- 단점: MDC 전파가 안 됨 → TaskDecorator를 Executor wrapper로 별도 구현 필요

**옵션 B: 스프링 6.1의 Virtual Thread 지원 사용**
```java
// application.yml에 추가
spring:
  threads:
    virtual:
      enabled: true
```
- Spring Boot 3.2+에서 Tomcat, `@Async` 등에 전체 Virtual Thread 적용
- 가장 간단하지만 전체 적용이므로 사이드 이펙트 검토 필요

**권장:** 옵션 B로 먼저 시도 후 부하 테스트(k6/Locust)로 처리량 비교.

**수정 파일:**
- `AsyncConfig.java` (옵션 A) 또는 `application.yml` (옵션 B)

---

## 3. 비동기 처리 보상 트랜잭션

### 3-A. `@TransactionalEventListener` 미적용 (핵심 버그)

**파일:** `AttendEventHandler.java`, `EventStoreHandler.java`

**현재 문제:**
```java
// FaceRecognitionService.authenticationByUserFace() — @Transactional
Events.raise(new AttendEvent(...));  // 트랜잭션 커밋 전에 이벤트 발행!
return FaceAuthResultResponse.of(...);
// ← 여기서 @Transactional 커밋
```

`@EventListener`는 `publishEvent()` 호출 즉시 실행됨.
`AttendEventHandler`가 `@Async`이므로 별도 스레드에서 실행되는데,
**face auth 트랜잭션이 아직 커밋되지 않은 상태**에서 Redis/DB 저장이 시작될 수 있음.
face auth가 롤백되면 입장 처리는 이미 완료된 상태가 되어 **유령 입장** 발생.

**해결 방향:**
- `AttendEventHandler`에 `@EventListener` → `@TransactionalEventListener(phase = AFTER_COMMIT)` 변경.
- 이렇게 하면 face auth 트랜잭션이 커밋된 후에만 비동기 입장 처리 시작.

```java
// AttendEventHandler.java
@Async("customTaskExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)  // 변경
public void handle(AttendEvent event) { ... }
```

- `EventStoreHandler`도 동일하게 적용: 트랜잭션 롤백 시 이벤트 저장 안 되도록.

```java
// EventStoreHandler.java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(Event event) {
    eventStore.save(event);
}
```

**수정 파일:**
- `AttendEventHandler.java`
- `EventStoreHandler.java`

---

### 3-B. Redis 저장 성공 후 DB 저장 실패 시 불일치

**파일:** `AttendEventHandler.java`

**현재 코드:**
```java
public void handle(AttendEvent event) {
    AttendInfo attendInfo = attendRedisService.saveUserAttendance(...); // Redis 카운트 증가
    sseService.sendAttendanceCount(...);                                  // SSE 전송
    if (attendInfo.isNewUser()) {
        attendService.registerAttend(...);    // DB 저장 — 여기서 실패하면?
    }
}
```

**문제:** Redis는 증가했지만 DB에는 기록 없음 → 참석자 수 카운트는 맞지만 상세 이력 누락.

**해결 방향:**

단계별 try-catch로 실패 지점 격리 + Redis 롤백 또는 재시도 로직:

```java
@Async("customTaskExecutor")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(AttendEvent event) {
    Long conferenceId = event.getConferenceId();
    Long sessionId = event.getSessionId();
    Long userId = event.getUserId();

    AttendInfo attendInfo;
    try {
        attendInfo = attendRedisService.saveUserAttendance(conferenceId, sessionId, userId);
    } catch (Exception e) {
        log.error("[AttendEventHandler] Redis 저장 실패 - userId={}, conferenceId={}", userId, conferenceId, e);
        return;  // Redis 실패 시 이후 작업 진행 불가
    }

    // SSE 전송 실패는 입장 처리에 영향 없어야 함
    try {
        sseService.sendAttendanceCount(conferenceId, sessionId, attendInfo.attendCount());
    } catch (Exception e) {
        log.warn("[AttendEventHandler] SSE 전송 실패 (무시) - conferenceId={}", conferenceId, e);
    }

    if (attendInfo.isNewUser()) {
        try {
            attendService.registerAttend(userId, conferenceId, sessionId);
        } catch (Exception e) {
            log.error("[AttendEventHandler] DB 저장 실패, Redis 롤백 시도 - userId={}", userId, e);
            // Redis 카운트 롤백
            attendRedisService.rollbackUserAttendance(conferenceId, sessionId, userId);
        }
    }
}
```

**추가 구현 필요:**
- `AttendRedisService.rollbackUserAttendance()`: Set에서 userId 제거, count 감소.

```java
// AttendRedisService.java에 추가
public void rollbackUserAttendance(Long conferenceId, Long sessionId, Long userId) {
    AttendKeys keys = generateKeys(conferenceId, sessionId);
    redisTemplate.opsForSet().remove(keys.attendanceKey(), userId.toString());
    redisTemplate.opsForValue().decrement(keys.countKey());
    log.warn("Redis 롤백 완료 - userId={}, conferenceId={}", userId, conferenceId);
}
```

**수정 파일:**
- `AttendEventHandler.java` — 단계별 try-catch 적용
- `AttendRedisService.java` — `rollbackUserAttendance()` 추가

---

### 3-C. Redis `incrementCountIfNew()` 원자성 문제

**파일:** `AttendRedisService.java`

**현재 코드:**
```java
private void incrementCountIfNew(String countKey) {
    boolean isNewKey = Boolean.FALSE.equals(redisTemplate.hasKey(countKey)); // 1. 체크
    redisTemplate.opsForValue().increment(countKey);                          // 2. 증가
    if (isNewKey) {
        expireAtNextDay5AM(countKey);                                          // 3. TTL 설정
    }
}
```

**문제:** 1과 2 사이에 다른 요청이 끼어들면 두 스레드 모두 `isNewKey=true`로 판정 → 카운트 2 증가, TTL 이중 설정.

**해결 방향:**
Redis Lua 스크립트로 check-increment-expire를 단일 원자 연산으로 처리:

```java
// DefaultRedisScript를 빈으로 등록하거나 메서드 내 생성
private static final String INCREMENT_AND_EXPIRE_SCRIPT =
    "local isNew = redis.call('EXISTS', KEYS[1]) == 0 \n" +
    "redis.call('INCR', KEYS[1]) \n" +
    "if isNew then \n" +
    "  redis.call('EXPIREAT', KEYS[1], ARGV[1]) \n" +
    "end \n" +
    "return redis.call('GET', KEYS[1])";

private void incrementCountIfNew(String countKey) {
    long expireEpoch = getNextDay5AMEpochSeconds();
    redisTemplate.execute(
        new DefaultRedisScript<>(INCREMENT_AND_EXPIRE_SCRIPT, Long.class),
        Collections.singletonList(countKey),
        String.valueOf(expireEpoch)
    );
}
```

**수정 파일:**
- `AttendRedisService.java`

---

## 4. 기타 발견된 문제들

### 4-A. `application-prod.yml` Redis Host 하드코딩

**파일:** `src/main/resources/application-prod.yml`

**현재:**
```yaml
data:
  redis:
    host: localhost   # 하드코딩!
```

**문제:** Docker Compose 환경에서 Redis 컨테이너는 `redis`라는 서비스명으로 접근해야 함. `localhost`면 연결 불가.

**해결:**
```yaml
data:
  redis:
    host: ${REDIS_HOST:localhost}
```

**수정 파일:**
- `application-prod.yml`

---

### 4-B. `build.gradle` 불필요 의존성

**파일:** `build.gradle`

**현재 문제들:**
1. SMS 기능 제거 예정인데 `net.nurigo:sdk:4.3.2` 의존성 유지 중
2. `net.coobird:thumbnailator:0.4.1` — S3 이미지 업로드에 사용되던 이미지 압축 라이브러리인데 `S3FileService.java` 삭제 후에도 남아 있음

**확인 후 제거 대상:**
```groovy
// 제거 대상
implementation 'net.nurigo:sdk:4.3.2'         // SMS 제거 시
implementation group: 'net.coobird', name: 'thumbnailator', version: '0.4.1'  // S3FileService 삭제 확인 후
```

**수정 파일:**
- `build.gradle`

---

### 4-C. `AttendRedisService.deleteAllKeys()` 운영 위험

**파일:** `AttendRedisService.java`

**현재 코드:**
```java
public void deleteAllKeys() {
    redisTemplate.delete(redisTemplate.keys("*"));  // 위험!
}
```

**문제:**
- `KEYS *`는 Redis의 **O(N) 블로킹 명령**. 키가 많을수록 Redis 전체가 멈춤.
- 운영 환경에서 실수로 호출하면 서비스 장애.
- API로 노출되어 있어 Swagger에서 누구나 호출 가능 (인증 없이).

**해결 방향:**
1. `SCAN` 명령으로 교체하거나 특정 패턴의 키만 삭제하도록 변경.
2. 해당 메서드는 Swagger 테스트용이므로 `@Profile("!prod")`로 운영 환경 비활성화.
3. 또는 해당 엔드포인트를 완전히 제거하고 Redis CLI로만 관리.

**수정 파일:**
- `AttendRedisService.java`
- 관련 Controller (있다면 `@Profile("!prod")` 추가 또는 엔드포인트 제거)

---

### 4-D. `EventStoreHandler`의 트랜잭션 설계 검토

**파일:** `EventStoreHandler.java`, `JpqlEventStore.java`

**현재 상태:**
```java
// EventStoreHandler.java
@EventListener(Event.class)          // 동기 실행, 호출자 트랜잭션 내에서 실행
public void handle(Event event) {
    eventStore.save(event);          // JpqlEventStore.save() — @Transactional(REQUIRED)
}
```

**문제:**
- `EventStoreHandler`는 `@Async`가 없어 face auth 트랜잭션 내에서 동기 실행됨.
- `JpqlEventStore.save()`가 face auth 트랜잭션에 참여(`REQUIRED` propagation)하므로, eventstore 저장 실패 시 face auth 전체 롤백됨 — 의도치 않은 동작.
- 반대로 eventstore는 저장되었는데 face auth가 롤백되면 커밋된 eventstore 레코드만 남는 문제도 있음.

**해결 방향:**
- `EventStoreHandler`를 `@TransactionalEventListener(phase = AFTER_COMMIT)`으로 변경하고, `JpqlEventStore.save()`는 `@Transactional(propagation = REQUIRES_NEW)`로 독립 트랜잭션 사용.

```java
// EventStoreHandler.java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handle(Event event) {
    eventStore.save(event);
}

// JpqlEventStore.java — propagation 변경
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void save(Object event) { ... }
```

**수정 파일:**
- `EventStoreHandler.java`
- `JpqlEventStore.java`

---

### 4-E. SSE `subscribe()` 재연결 시 초기 카운트 불일치

**파일:** `SseService.java`

**현재 코드:**
```java
private void sendToClientFirst(String eventKey, SseEmitter sseEmitter) {
    lastKnownCounts.putIfAbsent(eventKey, 0L);
    long baseAttendCount = lastKnownCounts.getOrDefault(eventKey, 0L);
    ...
}
```

**문제:**
- `lastKnownCounts`는 인메모리. 서버 재시작 시 초기화됨.
- 재시작 직후 재연결한 클라이언트는 Redis에 이미 카운트가 있더라도 0을 받게 됨.

**해결 방향:**
- `sendToClientFirst()`에서 `lastKnownCounts`에 값이 없으면 Redis에서 현재 카운트 조회.

```java
private void sendToClientFirst(String eventKey, SseEmitter sseEmitter) {
    long baseAttendCount = lastKnownCounts.computeIfAbsent(eventKey, key -> {
        // conferenceId, sessionId를 eventKey에서 파싱하거나 파라미터로 전달
        return attendRedisService.attendCount(conferenceId, sessionId);
    });
    ...
}
```

또는 `subscribe()` 메서드 시그니처에서 Redis 조회를 직접 수행하도록 수정.

**수정 파일:**
- `SseService.java` — `sendToClientFirst()` 또는 `subscribe()` 수정
- `AttendRedisService.java` — `attendCount()` 활용 (이미 존재)

---

### 4-F. `SseService`에 `@Async` 스레드 안전성

**파일:** `SseService.java`, `AttendEventHandler.java`

**현재 구조:**
```
[Tomcat 스레드] subscribe() → emitter 생성, HTTP 응답 스트림에 바인딩
[customTaskExecutor 스레드] sendAttendanceCount() → 동일 emitter에 send()
```

**잠재적 문제:**
- `SseEmitter`는 내부적으로 `ResponseBodyEmitter`를 통해 HTTP 응답에 쓰는데, 서로 다른 스레드에서 동시에 `send()`하면 `IllegalStateException` 발생 가능.
- 현재는 emitter당 하나의 이벤트 키이고 동시 전송이 거의 없어서 실제 발생 빈도 낮지만, 동시 입장자 폭증 시 문제 가능.

**해결 방향:**
- `emitter.send()` 호출을 `synchronized(emitter)` 블록으로 감싸거나,
- `SseEmitter`를 `Lock`으로 래핑한 커스텀 클래스 도입.
- 또는 emitter당 단일 스레드 dispatch를 보장하는 구조로 리팩토링.

**수정 파일:**
- `SseService.java` — `sendAttendanceCount()`, `subscribe()` 내 send 호출부

---

## 5. 작업 순서 요약

| 순서 | 작업 항목 | 수정 파일 | 난이도 | 위험도 |
|------|-----------|-----------|--------|--------|
| 1 | `application-prod.yml` Redis host 환경변수화 | `application-prod.yml` | 낮음 | 높음(운영 장애) |
| 2 | `sendAttendanceCount()` IOException 예외 처리 수정 | `SseService.java` | 낮음 | 높음(DB 불일치 유발) |
| 3 | `@TransactionalEventListener(AFTER_COMMIT)` 적용 | `AttendEventHandler.java`, `EventStoreHandler.java` | 낮음 | 높음(유령 입장 방지) |
| 4 | `AsyncUncaughtExceptionHandler` 구현 | `AsyncConfig.java` | 낮음 | 중간 |
| 5 | `RejectedExecutionHandler(CallerRunsPolicy)` 설정 | `AsyncConfig.java` | 낮음 | 중간 |
| 6 | `lastKnownCounts` 메모리 누수 수정 | `SseService.java` | 낮음 | 중간 |
| 7 | `subscribe()` Race Condition 해결 (원자적 교체) | `SseEmitterRepository.java`, `SseService.java` | 중간 | 높음 |
| 8 | `AttendEventHandler` 보상 트랜잭션 + Redis 롤백 | `AttendEventHandler.java`, `AttendRedisService.java` | 중간 | 높음 |
| 9 | Heartbeat 도입 (좀비 소켓 능동 감지) | `SseService.java`, `SseEmitterRepository.java` | 중간 | 중간 |
| 10 | `EventStoreHandler` 독립 트랜잭션 분리 | `EventStoreHandler.java`, `JpqlEventStore.java` | 중간 | 중간 |
| 11 | Redis 초기 카운트 불일치 수정 | `SseService.java` | 중간 | 낮음 |
| 12 | `incrementCountIfNew()` Lua 스크립트 원자화 | `AttendRedisService.java` | 중간 | 낮음 |
| 13 | `deleteAllKeys()` 위험 API 제한 | `AttendRedisService.java` | 낮음 | 높음(운영 보호) |
| 14 | `build.gradle` 불필요 의존성 제거 | `build.gradle` | 낮음 | 낮음 |
| 15 | Virtual Thread 도입 및 부하 테스트 비교 | `AsyncConfig.java` / `application.yml` | 높음 | 낮음 |

---

## 부록: 핵심 클래스 의존 관계

```
FaceRecognitionController
  └─ FaceRecognitionService (@Transactional)
       └─ Events.raise(AttendEvent)
            ├─ EventStoreHandler (@EventListener → AFTER_COMMIT으로 변경 필요)
            │    └─ JpqlEventStore.save() (REQUIRES_NEW 트랜잭션)
            └─ AttendEventHandler (@Async + @EventListener → AFTER_COMMIT으로 변경 필요)
                 ├─ AttendRedisService.saveUserAttendance()   [1단계: Redis]
                 ├─ SseService.sendAttendanceCount()          [2단계: SSE, 실패 무시]
                 └─ AttendService.registerAttend()            [3단계: DB, 실패 시 Redis 롤백]

SseController
  └─ SseService.subscribe()
       └─ SseEmitterRepository (ConcurrentHashMap)
            └─ [문제] Race Condition, lastKnownCounts 누수, 좀비 소켓
```
