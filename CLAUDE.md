# CLAUDE.md — mask-pass 리팩토링 가이드

## 프로젝트 개요

mask-pass는 대규모 컨퍼런스에서 얼굴 인증을 통해 입/퇴장을 처리하고,
컨퍼런스·세션별 실시간 참가자 수를 제공하는 서비스다.

**GitHub**: `sukangpunch/mask-pass`

---

## 기술 스택

| 분류 | 기술                                         |
|------|--------------------------------------------|
| Language | Java 21                                    |
| Framework | Spring Boot 3.4.3 (Servlet 스택)             |
| DB | PostgreSQL + JPA + QueryDSL 5.0            |
| Cache | Redis (Spring Data Redis)                  |
| AWS | S3 (이미지 저장), Rekognition (얼굴 인증)           |
| 비동기 | Spring `@Async` + `ThreadPoolTaskExecutor` |
| 실시간 | SSE (`SseEmitter`)                         |
| 이벤트 | Spring `ApplicationEventPublisher`         |
| 모니터링 | Actuator + Micrometer Prometheus           |
| 테스트 | JUnit 5, Mockito, Spring RestDocs          |
| 인프라 | Docker Compose, GitHub Actions CI/CD       |

---

## 패키지 구조

```
src/main/java/goorm/back/zo6/
├── attend/           # 참석 처리 (얼굴인증 후 입장 기록)
│   ├── application/  # AttendService
│   ├── domain/       # Attend, AttendEvent, AttendRepository
│   └── infrastructure/ # AttendEventHandler, AttendRedisService, AttendRepositoryImpl
├── common/
│   ├── config/       # AsyncConfig, AwsConfig, SwaggerConfig
│   └── event/        # Events, EventStoreHandler
├── conference/       # 컨퍼런스·세션 도메인
├── eventstore/       # 이벤트 저장소 (JpqlEventStore → PostgreSQL)
├── face/             # 얼굴 등록·인증 (RekognitionApiClient, FaceRecognitionService)
├── notice/           # 공지 (현재 SMS 기능 제거 예정)
├── qr/               # QR 코드 생성
├── reservation/      # 예약 도메인
├── sse/              # SSE 연결 관리 (SseService, SseEmitterRepository)
└── user/             # 사용자 도메인
```

---

## 핵심 비즈니스 플로우

### 얼굴 인증 → 입장 처리 흐름

```
FaceRecognitionController.authenticationByUserFace()
  └─ FaceRecognitionService.authenticationByUserFace()
       ├─ RekognitionApiClient.authorizeUserFace()   // AWS Rekognition 호출
       ├─ 예약 검증
       └─ Events.raise(new AttendEvent(...))          // 이벤트 발행
            └─ AttendEventHandler.handle()  [@Async]
                 ├─ AttendRedisService.saveUserAttendance()  // Redis 카운트 증가
                 ├─ SseService.sendAttendanceCount()         // SSE 실시간 전송
                 └─ AttendService.registerAttend()           // PostgreSQL 기록
```

### SSE 실시간 참석자 수 흐름

```
기기(태블릿 등) → GET /api/v1/sse/subscribe?conferenceId={id}&sessionId={id}
  └─ SseService.subscribe()
       ├─ SseEmitterRepository (ConcurrentHashMap 기반 인메모리)
       └─ TIMEOUT: 1800초 (30분)
```

---

## 현재 코드의 문제점 및 리팩토링 목표

### 1. SSE 연결 누적 → OOM

**현재 상태**
- `SseEmitterRepository`가 `ConcurrentHashMap<String, SseEmitter>`를 인메모리로 관리
- key가 `conference:{id}` 또는 `conference:{id}:session:{id}` 구조라 동일 기기가 재접속해도 기존 emitter가 정리되지 않는 케이스 존재
- `TIMEOUT`이 1800초(30분)로 장시간 연결 유지
- 네트워크 단절 시 `onError` → `deleteByEventKey` 처리가 되나, Tomcat의 CLOSE_WAIT 상태 연결은 감지 안 됨

**리팩토링 방향**
- subscribe 진입 시 기존 emitter 존재 확인 → `complete()` 후 교체하는 로직은 이미 존재하나 race condition 가능성 검토
- heartbeat(주기적 ping 전송) 도입으로 죽은 연결 능동적 감지
- emitter 생명주기 로깅 강화 및 메트릭 연동

---

### 2. 비동기 처리 실패 시 보상 트랜잭션 없음

**현재 상태**

```java
// AttendEventHandler.java
@Async("customTaskExecutor")
@EventListener(AttendEvent.class)
public void handle(AttendEvent event) {
    AttendInfo attendInfo = attendRedisService.saveUserAttendance(...); // Redis
    sseService.sendAttendanceCount(...);                                 // SSE
    if (attendInfo.isNewUser()) {
        attendService.registerAttend(...);                               // PostgreSQL
    }
}
```

- Redis 저장 성공 → SSE 전송 성공 → PostgreSQL 저장 **실패** 시 Redis와 DB 불일치 상태가 됨
- `@Async` 메서드에서 예외가 발생해도 호출부로 전파되지 않아 실패가 무시됨
- EventStore(`JpqlEventStore`)에 이벤트를 저장하는 `EventStoreHandler`가 별도로 존재하지만 재시도 로직 없음

**리팩토링 방향**
- `handle()` 내부에 try-catch 추가 후 실패 시 Redis 카운트 롤백 또는 별도 실패 이벤트 발행
- `AsyncUncaughtExceptionHandler` 구현으로 비동기 예외 중앙 처리
- EventStore를 활용한 재시도 가능 구조 검토 (현재 EventStore는 조회만 되고 재처리 로직 없음)

---

### 3. 비동기 활용 타이밍의 합리성

**현재 비동기 처리 목록**

| 클래스 | 메서드 | @Async 사용 근거 |
|--------|--------|----------------|
| `AttendEventHandler` | `handle()` | 얼굴인증 응답을 빠르게 반환하기 위해 입장처리를 비동기로 분리 → **합리적** |
| `PhoneValidService` | `sendValidMessage()` | SMS 발송 지연이 응답에 영향 주지 않도록 → **제거 예정 (SMS 기능 제거)** |
| `NoticeService` | `sendMessage()` | 대량 문자 발송 → **제거 예정 (SMS 기능 제거)** |

**검토 필요 사항**
- `AttendEventHandler.handle()` 내에서 SSE 전송(`sendAttendanceCount`)이 비동기 스레드에서 실행됨
- SSE emitter는 특정 HTTP 요청 스레드와 연결된 자원이므로, 다른 스레드에서 `emitter.send()` 호출 시 스레드 안전성 검토 필요

---

### 4. 스레드 풀 설정의 타당성

**현재 설정**

```java
// AsyncConfig.java
int processors = Runtime.getRuntime().availableProcessors(); // EC2 프리티어: 1
int corePoolSize = Math.max(2, processors);   // = 2
int maxPoolSize = Math.max(4, processors * 3); // = 4
int queueCapacity = 50;
```

**문제점**
- EC2 프리티어 기준 vCPU 1개 환경에서 maxPoolSize=4는 컨텍스트 스위칭 비용이 이득보다 클 수 있음
- 비동기 작업의 성격(I/O 대기 위주 vs CPU 연산 위주)에 따라 스레드 수 산정 기준이 다름
- 현재 비동기 작업: Redis I/O, PostgreSQL I/O, SSE 전송 → I/O bound 작업
- Java 23 Virtual Thread 도입 검토 가능 (I/O bound 작업에 적합)

**리팩토링 방향**
- Virtual Thread 도입 시 `ThreadPoolTaskExecutor` 대신 `Executors.newVirtualThreadPerTaskExecutor()` 사용 가능
- 성능 테스트를 통해 기존 스레드풀 vs Virtual Thread 처리량 비교

---

### 5. 기타 코드 개선 사항

- `build.gradle`: DynamoDB 의존성 미사용 → 제거 필요
- `build.gradle`: AWS SDK BOM 중복 선언(`2.20.85`, `2.20.112`) → 하나로 통합
- `build.gradle`: Java 버전 CD 파이프라인에서 21로 설정되어 있으나 프로젝트는 23 사용 → 수정 필요
- SMS 기능(`PhoneValidService`, `NoticeService`) 제거 시 `UserValidator`, `UserPhoneSignUpServiceImpl`, `UserController`, `NoticeController` 연쇄 수정 필요
- `application-prod.yml`: Redis `host: localhost` → `host: ${REDIS_HOST}` 변경 필요

---

## 성능 테스트 계획

### 테스트 대상 시나리오
1. 얼굴 인증 동시 요청 (다수 사용자가 동시에 입장하는 상황)
2. SSE 다중 구독 유지 상태에서 참석자 수 push 전송
3. 비동기 처리 (AttendEventHandler) 처리량 및 실패율

### 도구
- **Locust** 또는 **k6**: HTTP 부하 테스트
- **JaCoCo**: 코드 커버리지 (현재 INSTRUCTION 기준 30% 최소 설정)
- **Actuator + Prometheus + Grafana**: 실시간 메트릭 수집

### 측정 지표
- 얼굴 인증 API 응답 시간 (p50, p95, p99)
- SSE 연결 유지 중 메모리 사용량 (`emitterRepository.countEmitters()` 활용)
- 비동기 큐 대기(`queueCapacity=50`) 포화 시점
- Virtual Thread 전환 전후 처리량 비교

---

## 주요 파일 위치

```
src/main/java/goorm/back/zo6/
├── sse/application/SseService.java                    # SSE 핵심 로직
├── sse/infrastructure/SseEmitterRepository.java       # Emitter 저장소
├── attend/infrastructure/AttendEventHandler.java      # 비동기 이벤트 처리
├── attend/infrastructure/AttendRedisService.java      # Redis 참석자 수 관리
├── common/config/AsyncConfig.java                     # 스레드풀 설정
├── face/infrastructure/RekognitionApiClient.java      # AWS Rekognition 연동
├── eventstore/infrastructure/JpqlEventStore.java      # 이벤트 저장소
└── common/event/Events.java                           # 이벤트 발행 유틸

src/main/resources/
├── application-prod.yml                               # 운영 환경 설정
└── application-test.yml                               # 테스트 환경 설정

docker-compose.prod.yml                                # 운영 Docker Compose
.github/workflows/cd.yml                               # CD 파이프라인
build.gradle                                           # 의존성 관리
```

---

## 작업 우선순위

| 우선순위 | 작업 | 관련 파일 |
|---------|------|----------|
| 1 | SMS 기능 제거 및 연관 클래스 정리 | `PhoneValidService`, `NoticeService`, `UserValidator`, `UserPhoneSignUpServiceImpl` |
| 2 | `build.gradle` 불필요 의존성 제거 | `build.gradle` |
| 3 | SSE 연결 누적 문제 해결 (heartbeat 도입) | `SseService`, `SseEmitterRepository` |
| 4 | 비동기 실패 시 보상 처리 추가 | `AttendEventHandler`, `AsyncConfig` |
| 5 | Virtual Thread 전환 검토 및 성능 테스트 | `AsyncConfig` |
| 6 | EventStore 기반 재시도 구조 설계 | `JpqlEventStore`, `EventStoreHandler` |

---

## 테스트 실행

```bash
# 전체 테스트 (단위 테스트만 가능, 통합 테스트는 AWS 자격증명 필요)
./gradlew test

# 커버리지 리포트 생성 (INSTRUCTION 기준 30% 미달 시 빌드 실패)
./gradlew jacocoTestReport

# 빌드 (테스트 제외)
./gradlew bootJar -x test
```

### 테스트 환경 주의사항
- 단위 테스트(`@ExtendWith(MockitoExtension.class)`): AWS 불필요
- 통합 테스트(`@SpringBootTest`, `@ActiveProfiles("test")`): AWS 자격증명 필수
- `RekognitionApiClient`, `FaceRecognitionService` 등 AWS 연동 클래스는 Mockito로 격리

---

## 환경변수 목록 (운영)

| 변수명 | 용도 |
|--------|------|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | PostgreSQL |
| `REDIS_HOST` | Redis 호스트 (`docker-compose`에서 서비스명 `redis`) |
| `JWT_SECRET` | JWT 서명 키 |
| `AWS_ACCESS_KEY`, `AWS_SECRET_ACCESS_KEY` | AWS 인증 |
| `REKOGNITION_COLLECTION_ID` | Rekognition Collection ID |
| `S3_BUCKET_NAME` | S3 버킷명 |
| `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI` | 카카오 OAuth2 |
| `SERVER_URL` | 서버 URL |
| `COOKIE_NAME` | JWT 쿠키명 |
| `DISCORD_WEBHOOK_URL` | Discord 로그 알림 |
