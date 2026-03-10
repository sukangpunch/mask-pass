package goorm.back.zo6.attend.infrastructure;

import goorm.back.zo6.attend.application.AttendService;
import goorm.back.zo6.attend.domain.AttendEvent;
import goorm.back.zo6.common.config.AsyncExceptionHandler;
import goorm.back.zo6.common.event.Events;
import goorm.back.zo6.eventstore.api.EventStore;
import goorm.back.zo6.sse.application.SseService;
import goorm.back.zo6.support.TestContainerSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * AttendEventHandler 통합 테스트
 *
 * - AttendRedisService: @MockitoSpyBean → 실제 Redis 동작 + 호출 검증
 * - AttendService:      @MockitoBean    → DB 저장 동작 제어
 * - EventStore:         @MockitoBean    → EventStoreHandler 격리 (모든 이벤트 자동 저장 방지)
 * - SseService:         @MockitoSpyBean → SSE 격리 동작 검증
 * - AttendRetryScheduler: @MockitoBean → 스케줄러가 테스트 중 실행되지 않도록 격리
 */
@ExtendWith(OutputCaptureExtension.class)
@TestContainerSpringBootTest
class AttendEventHandlerTest {

    @MockitoSpyBean
    private AttendRedisService attendRedisService;

    @MockitoBean
    private AttendService attendService;

    @MockitoBean
    private EventStore eventStore; // EventStoreHandler가 자동 저장하는 것을 mock으로 격리

    @MockitoSpyBean
    private SseService sseService;

    @MockitoSpyBean
    private AsyncExceptionHandler asyncExceptionHandler;

    @MockitoBean
    private AttendRetryScheduler attendRetryScheduler;

    @BeforeEach
    void setUp() {
        attendRedisService.deleteAllKeys();
        clearInvocations(attendRedisService, attendService, sseService, asyncExceptionHandler);
    }

    // -------------------------------------------------------------------------
    // 정상 경로
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("신규 유저 참석 처리 성공 - Redis +1, SSE 전송, DB 저장 1회 호출")
    void handle_NewUser_Success(CapturedOutput output) {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(1L);
            verify(sseService).sendAttendanceCount(conferenceId, sessionId, 1L);
            verify(attendService).registerAttend(userId, conferenceId, sessionId);
        });
        // 롤백/실패 큐 적재 없음
        verify(attendRedisService, never()).rollbackAttendance(any(), any(), any());
        verify(attendRedisService, never()).pushFailedEvent(any(), any(), any());
    }

    @Test
    @DisplayName("기존 유저 재참석 - 카운트 그대로, DB 저장 미호출")
    void handle_ExistingUser_SkipsDbSave() {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        attendRedisService.saveUserAttendance(conferenceId, sessionId, userId);

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(sseService).sendAttendanceCount(eq(conferenceId), eq(sessionId), anyLong())
        );
        assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(1L);
        verify(attendService, never()).registerAttend(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // SSE 격리
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[SSE 격리] SSE 실패해도 DB 저장이 정상 실행되고 Redis count 유지됨")
    void handle_WhenSseFails_DbSaveStillExecuted(CapturedOutput output) {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("SSE 전송 실패"))
                .when(sseService).sendAttendanceCount(eq(conferenceId), eq(sessionId), anyLong());

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(attendService).registerAttend(userId, conferenceId, sessionId)
        );
        assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(1L);
        assertThat(output.getAll()).contains("[SSE 전송 실패]");
        verify(attendRedisService, never()).pushFailedEvent(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // DB 실패 → Redis 롤백 + 실패 큐 적재
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[DB 실패] DB 저장 실패 - Redis 롤백(count=0), 실패 이벤트 큐 적재")
    void handle_WhenDbFails_RollbackAndEnqueued(CapturedOutput output) {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("DB 오류"))
                .when(attendService).registerAttend(userId, conferenceId, sessionId);

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(attendService).registerAttend(userId, conferenceId, sessionId);
            verify(attendRedisService).rollbackAttendance(conferenceId, sessionId, userId);
            verify(attendRedisService).pushFailedEvent(userId, conferenceId, sessionId);
        });
        // Redis는 롤백되어 count = 0
        assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(0L);
        assertThat(output.getAll()).contains("[DB 저장 실패]", "[실패 이벤트 저장]");
    }

    @Test
    @DisplayName("[DB 실패] 실패 큐 적재도 실패하면 CRITICAL 로그 출력")
    void handle_WhenEnqueueFails_CriticalLogAppears(CapturedOutput output) {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("DB 오류"))
                .when(attendService).registerAttend(userId, conferenceId, sessionId);
        doThrow(new RuntimeException("Redis 큐 적재 실패"))
                .when(attendRedisService).pushFailedEvent(userId, conferenceId, sessionId);

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(output.getAll()).contains("[CRITICAL]", "즉각 수동 확인 필요")
        );
    }

    @Test
    @DisplayName("[DB 실패] Redis 롤백 실패해도 실패 이벤트 큐 적재는 계속 시도됨")
    void handle_WhenRollbackFails_EnqueueStillAttempted(CapturedOutput output) {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("DB 오류"))
                .when(attendService).registerAttend(userId, conferenceId, sessionId);
        doThrow(new RuntimeException("Redis 롤백 실패"))
                .when(attendRedisService).rollbackAttendance(conferenceId, sessionId, userId);

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(output.getAll()).contains("[CRITICAL]", "Redis 롤백 실패");
            verify(attendRedisService).pushFailedEvent(userId, conferenceId, sessionId);
        });
    }

    // -------------------------------------------------------------------------
    // AsyncUncaughtExceptionHandler
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("[AsyncUncaughtExceptionHandler] DB 실패는 내부 처리 — 핸들러 미호출")
    void asyncExceptionHandler_NotCalledWhenDbFails() {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("DB 오류"))
                .when(attendService).registerAttend(userId, conferenceId, sessionId);

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(attendRedisService).pushFailedEvent(userId, conferenceId, sessionId)
        );
        verify(asyncExceptionHandler, never()).handleUncaughtException(any(), any(), any());
    }

    @Test
    @DisplayName("[AsyncUncaughtExceptionHandler] Redis 실패는 try-catch 밖 — 핸들러 호출됨")
    void asyncExceptionHandler_CalledWhenRedisFails() {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("Redis 연결 실패"))
                .when(attendRedisService).saveUserAttendance(conferenceId, sessionId, userId);

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(asyncExceptionHandler).handleUncaughtException(
                        any(RuntimeException.class), any(Method.class), any())
        );
    }

    @Test
    @DisplayName("[AsyncUncaughtExceptionHandler] 정상 처리 시 핸들러 미호출")
    void asyncExceptionHandler_NotCalledOnSuccess() {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;

        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(attendService).registerAttend(userId, conferenceId, sessionId)
        );
        verify(asyncExceptionHandler, never()).handleUncaughtException(any(), any(), any());
    }
}
