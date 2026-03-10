package goorm.back.zo6.attend.infrastructure;

import goorm.back.zo6.attend.application.AttendService;
import goorm.back.zo6.attend.domain.AttendEvent;
import goorm.back.zo6.common.config.AsyncExceptionHandler;
import goorm.back.zo6.common.event.Events;
import goorm.back.zo6.sse.application.SseService;
import goorm.back.zo6.support.TestContainerSpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
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
 * AsyncUncaughtExceptionHandler 개선 전/후 로그 출력 비교 테스트
 *
 * [개선 전] getAsyncUncaughtExceptionHandler() 미등록
 *   → Spring 기본 SimpleAsyncUncaughtExceptionHandler 작동
 *   → WARN 레벨: "Unexpected exception occurred invoking async method"
 *   → 애플리케이션 코드에서 예외 감지 불가, 모니터링 연계 불가
 *
 * [개선 후] AsyncExceptionHandler 등록
 *   → ERROR 레벨: "[Async 예외] method=handle, ..."
 *   → handleUncaughtException() 호출 검증 가능, 알람/모니터링 연계 가능
 */
@ExtendWith(OutputCaptureExtension.class)
@TestContainerSpringBootTest
class AsyncExceptionHandlerComparisonTest {

    @MockitoBean
    private AttendService attendService;

    @MockitoSpyBean
    private SseService sseService;

    @MockitoSpyBean
    private AsyncExceptionHandler asyncExceptionHandler;

    @Autowired
    private AttendRedisService attendRedisService;

    @BeforeEach
    void setUp() {
        attendRedisService.deleteAllKeys();
        clearInvocations(attendService, sseService, asyncExceptionHandler);
    }

    @Test
    @DisplayName("[개선 전 시뮬레이션] 핸들러가 아무것도 안 하면 @Async 예외가 ERROR 로그 없이 소멸됨")
    void before_HandlerDoesNothing_NoErrorLog(CapturedOutput output) {
        // given - 핸들러를 no-op으로 만들어 '개선 전' 상태 시뮬레이션
        // 실제 개선 전: getAsyncUncaughtExceptionHandler()가 null → Spring이 SimpleAsyncUncaughtExceptionHandler(WARN) 사용
        // 시뮬레이션: spy를 doNothing()으로 설정 → 우리 ERROR 로그가 출력되지 않음
        doNothing().when(asyncExceptionHandler).handleUncaughtException(
                any(Throwable.class), any(Method.class), any());

        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("DB 저장 실패"))
                .when(attendService).registerAttend(userId, conferenceId, sessionId);

        // when
        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        // then - 핸들러는 호출됐지만 아무것도 하지 않음
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() ->
                verify(asyncExceptionHandler).handleUncaughtException(
                        any(RuntimeException.class), any(Method.class), any())
        );

        // 로그에 우리 ERROR 메시지 없음 → 예외가 애플리케이션 레벨에서 감지되지 않은 것처럼 보임
        assertThat(output.getAll())
                .as("개선 전: [Async 예외] ERROR 로그가 출력되지 않아야 함")
                .doesNotContain("[Async 예외]");

        System.out.println("\n=== [개선 전] 전체 로그 출력 ===");
        System.out.println(output.getAll());
    }

    @Test
    @DisplayName("[개선 후] 핸들러가 등록되면 @Async 예외가 ERROR 로그로 기록되고 애플리케이션에서 감지됨")
    void after_HandlerActive_ErrorLogAppears(CapturedOutput output) {
        // given - spy의 실제 메서드(ERROR 로그 출력)가 그대로 동작
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        doThrow(new RuntimeException("DB 저장 실패"))
                .when(attendService).registerAttend(userId, conferenceId, sessionId);

        // when
        Events.raise(new AttendEvent(userId, conferenceId, sessionId));

        // then - 핸들러 호출 + ERROR 로그 출력 모두 검증
        await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(asyncExceptionHandler).handleUncaughtException(
                    any(RuntimeException.class), any(Method.class), any());
            // 우리 핸들러가 ERROR 레벨로 기록한 메시지가 로그에 있음
            assertThat(output.getAll())
                    .as("개선 후: [Async 예외] ERROR 로그가 출력되어야 함")
                    .contains("[Async 예외]");
        });

        System.out.println("\n=== [개선 후] 전체 로그 출력 ===");
        System.out.println(output.getAll());
    }
}
