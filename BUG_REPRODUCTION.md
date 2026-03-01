# 버그 재연 및 해결 기록

> 목적: 리팩토링 전 버그를 재연·기록하고, 수정 후 동일 조건에서 검증한다.
> 환경: local profile (Docker Compose Redis + PostgreSQL, AWS는 Mock)

---

## 환경 준비

```bash
# 1. 로컬 Redis + PostgreSQL 기동
docker-compose -f docker-compose.local.yml up -d

# 2. 서버 실행 (local 프로파일)
./gradlew bootRun --args='--spring.profiles.active=local'

# 3. 기동 확인
curl http://localhost:8080/actuator/health
```

> AWS(Rekognition, S3)가 없어도 테스트 가능하도록 각 재연마다 Mock 설정을 포함한다.

---

## Bug-1. `subscribe()` Race Condition — 동시 재연결 시 좀비 Emitter 잔류

### 현상
프론트 화면 새로고침(빠른 재연결) 시 기존 emitter가 `complete()` 처리되지 않고
`SseEmitterRepository` Map에 잔류한다.

### 재연 코드
`src/test/java/goorm/back/zo6/sse/application/SseRaceConditionTest.java` 를 신규 작성

```java
package goorm.back.zo6.sse.application;

import goorm.back.zo6.sse.infrastructure.SseEmitterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [재연] subscribe() 동시 호출 시 Race Condition
 * - 두 스레드가 동시에 같은 eventKey로 subscribe()를 호출하면
 *   기존 emitter가 complete() 처리 없이 교체되거나 맵에 두 개가 남는다.
 */
class SseRaceConditionTest {

    @Test
    @DisplayName("[BUG-1] 동시 subscribe → 좀비 emitter 잔류 재연")
    void reproduce_raceCondition_on_concurrent_subscribe() throws InterruptedException {
        SseEmitterRepository repository = new SseEmitterRepository();
        SseService sseService = new SseService(repository);

        String eventKey = "conference:1";
        int threadCount = 2;
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done  = new CountDownLatch(threadCount);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();          // 동시에 출발
                    sseService.subscribe(1L, null);
                } catch (Exception e) {
                    System.err.println("subscribe 중 예외: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();  // 동시 실행 시작
        done.await();

        int emitterCount = repository.countEmitters();
        System.out.println("[BUG-1] 남아있는 emitter 수: " + emitterCount);

        /*
         * [기대 — 수정 전] emitterCount가 2일 수도 있고,
         *   기존 emitter가 complete() 안 된 채로 교체되면서 참조 누수가 생긴다.
         * [기대 — 수정 후] emitterCount == 1 이어야 한다.
         */
        assertThat(emitterCount).isEqualTo(1);  // 수정 전: FAIL 예상
    }
}
```

### 수정 전 로그 예시
```
[BUG-1] 남아있는 emitter 수: 2   ← race condition 발생 시
또는
기존 SSE 연결이 유지 중: conference:1  ← 잘못된 상태 판단으로 complete() 호출
```

### 수정 후 기대 결과
```
[BUG-1] 남아있는 emitter 수: 1   ← PASS
```

---

## Bug-2. `lastKnownCounts` 메모리 누수 — emitter 해제 후 Map 키 잔류

### 현상
emitter가 timeout / error / complete으로 제거되어도
`SseService` 내부의 `lastKnownCounts` Map에는 해당 key가 남아 메모리가 계속 증가한다.

### 재연 코드
`src/test/java/goorm/back/zo6/sse/application/SseMemoryLeakTest.java` 를 신규 작성

```java
package goorm.back.zo6.sse.application;

import goorm.back.zo6.sse.infrastructure.SseEmitterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [재연] onCompletion/onTimeout/onError 후 lastKnownCounts 키 잔류
 */
class SseMemoryLeakTest {

    @Test
    @DisplayName("[BUG-2] emitter 완료 후 lastKnownCounts에 키 잔류 재연")
    void reproduce_lastKnownCounts_leak_after_completion() {
        SseEmitterRepository repository = new SseEmitterRepository();
        SseService sseService = new SseService(repository);

        // 구독 → lastKnownCounts에 키 추가됨
        SseEmitter emitter = sseService.subscribe(1L, null);

        // emitter 수동 종료 (onCompletion 트리거)
        emitter.complete();

        // 내부 lastKnownCounts Map 확인 (리플렉션)
        @SuppressWarnings("unchecked")
        Map<String, Long> lastKnownCounts =
            (Map<String, Long>) ReflectionTestUtils.getField(sseService, "lastKnownCounts");

        System.out.println("[BUG-2] emitter 완료 후 lastKnownCounts 크기: " + lastKnownCounts.size());
        System.out.println("[BUG-2] 남은 키: " + lastKnownCounts.keySet());

        /*
         * [기대 — 수정 전] size == 1 (키가 제거되지 않음) → 장기 운영 시 무한 증가
         * [기대 — 수정 후] size == 0
         */
        assertThat(lastKnownCounts).isEmpty();  // 수정 전: FAIL 예상
    }
}
```

### 수정 전 로그 예시
```
[BUG-2] emitter 완료 후 lastKnownCounts 크기: 1
[BUG-2] 남은 키: [conference:1]    ← 누수 확인
```

---

## Bug-3. `sendAttendanceCount` IOException → 이후 DB 저장 중단

### 현상
클라이언트가 SSE 연결을 끊은 상태에서 얼굴 인증이 발생하면:
1. Redis 카운트는 증가
2. `sendAttendanceCount()`에서 IOException → `CustomException` throw
3. `@Async` 스레드에서 예외가 throw 되어 이후 `registerAttend()`(DB 저장)가 **실행되지 않음**
4. 결과: Redis 카운트는 1 증가, DB에는 기록 없음

### 재연 코드
`src/test/java/goorm/back/zo6/attend/infrastructure/AttendEventHandlerBugTest.java` 를 신규 작성

```java
package goorm.back.zo6.attend.infrastructure;

import goorm.back.zo6.attend.application.AttendService;
import goorm.back.zo6.attend.domain.AttendEvent;
import goorm.back.zo6.attend.dto.AttendInfo;
import goorm.back.zo6.sse.application.SseService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * [재연] sendAttendanceCount() IOException 발생 시 registerAttend() 미실행
 *
 * AttendEventHandler.handle() 흐름:
 *   1. attendRedisService.saveUserAttendance() → Redis 저장 (성공)
 *   2. sseService.sendAttendanceCount()        → IOException → CustomException throw
 *   3. attendService.registerAttend()           → 실행되지 않음!
 */
@ExtendWith(MockitoExtension.class)
class AttendEventHandlerBugTest {

    @InjectMocks
    private AttendEventHandler attendEventHandler;

    @Mock
    private AttendRedisService attendRedisService;

    @Mock
    private AttendService attendService;

    @Mock
    private SseService sseService;

    @Test
    @DisplayName("[BUG-3] SSE IOException 발생 시 DB 저장 미실행 재연")
    void reproduce_dbSave_skipped_when_sse_throws() {
        // given
        Long conferenceId = 1L;
        Long sessionId    = 2L;
        Long userId       = 10L;

        AttendEvent event = new AttendEvent(userId, conferenceId, sessionId);

        // Redis 저장 성공 (신규 유저)
        AttendInfo attendInfo = AttendInfo.of(true, 1L);
        when(attendRedisService.saveUserAttendance(conferenceId, sessionId, userId))
            .thenReturn(attendInfo);

        // SSE 전송 실패 시뮬레이션 (클라이언트 연결 끊김)
        doThrow(new goorm.back.zo6.common.exception.CustomException(
                    goorm.back.zo6.common.exception.ErrorCode.SSE_CONNECTION_FAILED))
            .when(sseService).sendAttendanceCount(anyLong(), anyLong(), anyLong());

        // when
        try {
            attendEventHandler.handle(event);
        } catch (Exception e) {
            System.out.println("[BUG-3] handle() 에서 예외 발생: " + e.getClass().getSimpleName());
        }

        // then
        System.out.println("[BUG-3] Redis 저장 호출 여부 확인...");
        verify(attendRedisService, times(1)).saveUserAttendance(conferenceId, sessionId, userId);

        System.out.println("[BUG-3] DB 저장 호출 여부 확인...");
        /*
         * [기대 — 수정 전] registerAttend() 호출 횟수 == 0 (SSE 예외로 중단)  → FAIL
         * [기대 — 수정 후] registerAttend() 호출 횟수 == 1                    → PASS
         */
        verify(attendService, times(1)).registerAttend(userId, conferenceId, sessionId);
    }
}
```

### 수정 전 로그 예시
```
[BUG-3] handle() 에서 예외 발생: CustomException
[BUG-3] DB 저장 호출 여부 확인...
Wanted but not invoked: attendService.registerAttend(...)  ← FAIL
```

---

## Bug-4. `@EventListener` — face auth 롤백 후에도 입장 처리 실행 (유령 입장)

### 현상
`Events.raise()`는 `@Transactional` 메서드 내부에서 커밋 전에 호출된다.
`@EventListener`는 `publishEvent()` 즉시 실행되므로, face auth 트랜잭션이 롤백되어도
Redis/DB 입장 처리가 이미 완료될 수 있다.

### 재연 코드
`src/test/java/goorm/back/zo6/test/GhostAttendanceTest.java` 를 신규 작성

```java
package goorm.back.zo6.test;

import goorm.back.zo6.attend.domain.AttendEvent;
import goorm.back.zo6.attend.infrastructure.AttendEventHandler;
import goorm.back.zo6.attend.infrastructure.AttendRedisService;
import goorm.back.zo6.attend.application.AttendService;
import goorm.back.zo6.common.event.Events;
import goorm.back.zo6.sse.application.SseService;
import goorm.back.zo6.attend.dto.AttendInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * [재연] @EventListener 사용 시 트랜잭션 롤백 후에도 AttendEventHandler 실행
 *
 * 올바른 동작: 트랜잭션 커밋 성공 시에만 입장 처리
 * 버그 동작:  트랜잭션 롤백 후에도 입장 처리 실행 → 유령 입장
 *
 * 전제: @SpringBootTest + local Redis 필요 (docker-compose.local.yml 기동 후 실행)
 */
@SpringBootTest
@ActiveProfiles("local")
class GhostAttendanceTest {

    @Autowired
    private ApplicationEventPublisher publisher;

    @MockitoBean
    private AttendRedisService attendRedisService;

    @MockitoBean
    private AttendService attendService;

    @MockitoBean
    private SseService sseService;

    @Test
    @DisplayName("[BUG-4] 트랜잭션 롤백 후에도 AttendEventHandler 실행 재연")
    void reproduce_ghost_attendance_on_rollback() throws InterruptedException {
        when(attendRedisService.saveUserAttendance(anyLong(), anyLong(), anyLong()))
            .thenReturn(AttendInfo.of(true, 1L));

        // 트랜잭션 내에서 이벤트 발행 후 강제 롤백 시뮬레이션
        try {
            publishEventAndRollback();
        } catch (Exception e) {
            System.out.println("[BUG-4] 트랜잭션 롤백 발생: " + e.getMessage());
        }

        // 비동기 처리 대기 (최대 3초)
        Thread.sleep(3000);

        System.out.println("[BUG-4] Redis 저장 호출 횟수 확인...");
        /*
         * [기대 — 수정 전(@EventListener)] 0이어야 하는데 1 → 유령 입장 FAIL
         * [기대 — 수정 후(@TransactionalEventListener AFTER_COMMIT)] 0 → PASS
         */
        verify(attendRedisService, times(0))
            .saveUserAttendance(anyLong(), anyLong(), anyLong());
    }

    @Transactional  // 이 메서드 전체가 트랜잭션
    void publishEventAndRollback() {
        // 이벤트 발행 (커밋 전)
        publisher.publishEvent(new AttendEvent(1L, 1L, 1L));
        // 강제 롤백
        throw new RuntimeException("의도적 롤백 — face auth 실패 시뮬레이션");
    }
}
```

### 수정 전 로그 예시
```
[BUG-4] 트랜잭션 롤백 발생: 의도적 롤백 — face auth 실패 시뮬레이션
[AttendEventHandler] Redis 저장 완료 userId=1  ← 롤백됐는데 실행됨!
[BUG-4] Redis 저장 호출 횟수: 1  → FAIL (유령 입장 확인)
```

---

## Bug-5. Redis `incrementCountIfNew()` — 동시 입장 시 카운트 중복 증가

### 현상
두 스레드가 동시에 같은 conferenceId로 입장하면,
`hasKey()` 체크와 `increment()` 사이에 race가 발생해 카운트가 2 증가할 수 있다.

### 재연 코드
`src/test/java/goorm/back/zo6/attend/infrastructure/AttendRedisRaceTest.java` 를 신규 작성

```java
package goorm.back.zo6.attend.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [재연] 동시 입장 시 Redis count 중복 증가
 * 전제: docker-compose.local.yml 기동 후 실행 (실제 Redis 필요)
 */
@SpringBootTest
@ActiveProfiles("local")
class AttendRedisRaceTest {

    @Autowired
    private AttendRedisService attendRedisService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    void cleanup() {
        // 테스트 전 관련 키 정리
        redisTemplate.delete("conference_count:999");
        redisTemplate.delete("conference:999");
    }

    @Test
    @DisplayName("[BUG-5] 동시 첫 입장 시 카운트 중복 증가 재연")
    void reproduce_count_doubleIncrement_on_concurrent_first_attend() throws InterruptedException {
        int threadCount = 10;
        // 서로 다른 userId 10명이 동시에 같은 conferenceId에 첫 입장
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (long userId = 1; userId <= threadCount; userId++) {
            final long uid = userId;
            pool.submit(() -> {
                try {
                    attendRedisService.saveUserAttendance(999L, null, uid);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        long count = attendRedisService.attendCount(999L, null);
        System.out.println("[BUG-5] 10명 동시 입장 후 카운트: " + count + " (기대값: 10)");

        /*
         * race condition 발생 시 카운트가 10보다 크거나 작을 수 있음.
         * 단, hasKey()→increment() 사이의 race는 count 초과보다 TTL 누락이 주 문제.
         * Lua 스크립트 적용 후에는 항상 10이어야 함.
         */
        assertThat(count).isEqualTo(10L);
    }
}
```

---

## Bug-6. 좀비 소켓 수동 확인 — Heartbeat 미존재 상태

### 현상
클라이언트가 CLOSE_WAIT 상태(네트워크 단절 후 FIN 미전송)로 끊겨도
서버는 emitter를 Map에 계속 유지한다.

### 재연 절차 (수동)

```bash
# 터미널 A: SSE 연결 (백그라운드)
curl -N "http://localhost:8080/api/v1/sse/subscribe?conferenceId=1" &
CURL_PID=$!
echo "curl PID: $CURL_PID"

# 서버 로그 확인 — emitter 등록 로그:
# === SSE Emitter 개수: 1 ===

# 터미널 A: 강제로 네트워크 단절 시뮬레이션 (SIGKILL, FIN 없이 종료)
kill -9 $CURL_PID

# 잠시 대기 후 emitter 개수 확인 (Actuator 또는 재구독으로)
sleep 5
curl -N "http://localhost:8080/api/v1/sse/subscribe?conferenceId=1" &
# 서버 로그 기대: "기존 SSE 연결이 유지 중: conference:1" (좀비 감지)
# 또는:          아무 로그 없이 기존 emitter가 그냥 덮어써짐 → 좀비 참조 남음
```

### 수정 전 관찰 포인트
- `emitterRepository.countEmitters()` 값이 감소하지 않음
- 서버 로그에 emitter 제거 관련 메시지 없음
- Actuator `/actuator/metrics` 에 emitter 수 메트릭 없음 (추가 필요)

### 수정 후 기대
- 30초 주기 heartbeat 로그 출력:
  ```
  Heartbeat 실패, 좀비 emitter 제거: key=conference:1
  ```

---

## Bug-7. `application-prod.yml` Redis localhost — 운영 배포 시 연결 실패

### 현상
운영 환경에서 Redis가 Docker 컨테이너 `redis` 서비스명으로 실행되는데,
설정이 `localhost`로 고정되어 있어 연결이 실패한다.

### 재연 절차

```bash
# docker-compose.prod.yml로 기동 시도
# (실제 AWS 환경변수 없어도 Redis 연결 실패는 확인 가능)
SPRING_PROFILES_ACTIVE=prod \
DB_URL=jdbc:postgresql://localhost:5432/mask_pass_db \
DB_USERNAME=postgres DB_PASSWORD=postgres \
JWT_SECRET=testsecret \
./gradlew bootRun

# 기대 로그 (수정 전):
# Cannot connect to Redis: localhost:6379 → 컨테이너 내부에서 localhost는 자기 자신
```

### 수정 후
```yaml
# application-prod.yml
host: ${REDIS_HOST:localhost}   # 환경변수 없으면 localhost fallback
```

---

## 재연 결과 기록 표

| Bug ID | 내용 | 재연 방법 | 수정 전 결과 | 수정 후 결과 | 해결일 |
|--------|------|-----------|-------------|-------------|--------|
| BUG-1 | subscribe() Race Condition | `SseRaceConditionTest` | emitter 2개 잔류 | emitter 1개 | - |
| BUG-2 | lastKnownCounts 메모리 누수 | `SseMemoryLeakTest` | size=1 (키 잔류) | size=0 | - |
| BUG-3 | SSE IOException → DB 저장 중단 | `AttendEventHandlerBugTest` | registerAttend 미실행 | registerAttend 실행 | - |
| BUG-4 | 트랜잭션 롤백 후 유령 입장 | `GhostAttendanceTest` | Redis 저장 실행됨 | Redis 저장 안 됨 | - |
| BUG-5 | Redis count 동시 증가 race | `AttendRedisRaceTest` | count ≠ 10 가능 | count == 10 | - |
| BUG-6 | 좀비 소켓 감지 불가 | 수동 curl + kill -9 | emitter 잔류 | heartbeat 로그 출력 | - |
| BUG-7 | prod Redis localhost 하드코딩 | 운영 배포 시 연결 실패 | 연결 오류 | 정상 연결 | - |

---

## 테스트 실행 순서

### 단위 테스트 (Redis/DB/AWS 불필요)
```bash
./gradlew test --tests "*.SseRaceConditionTest"
./gradlew test --tests "*.SseMemoryLeakTest"
./gradlew test --tests "*.AttendEventHandlerBugTest"
```

### 통합 테스트 (Redis 필요 — docker-compose.local.yml 기동 후)
```bash
./gradlew test --tests "*.AttendRedisRaceTest"
./gradlew test --tests "*.GhostAttendanceTest"
```

### 수동 테스트
```bash
# Bug-6 좀비 소켓
curl -N "http://localhost:8080/api/v1/sse/subscribe?conferenceId=1" &
kill -9 $!
```
