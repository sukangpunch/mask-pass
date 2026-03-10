package goorm.back.zo6.attend.infrastructure;

import goorm.back.zo6.attend.application.AttendService;
import goorm.back.zo6.attend.domain.AttendRepository;
import goorm.back.zo6.eventstore.api.EventStore;
import goorm.back.zo6.support.TestContainerSpringBootTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AttendRetryScheduler 통합 테스트
 *
 * 검증 목표: DB 저장 실패로 Redis 큐에 적재된 이벤트를 스케줄러가 재처리할 때
 * - DB 저장(AttendService.registerAttend) 호출 여부
 * - Redis 참석자 카운트 재반영(AttendRedisService.saveUserAttendance) 여부
 * - 성공 시 큐에서 제거, 실패 시 큐에 유지 여부
 *
 * - AttendRedisService: @MockitoSpyBean → 실제 Redis 동작 + 호출 검증
 * - AttendService:      @MockitoBean    → DB 동작 제어
 * - EventStore:         @MockitoBean    → EventStoreHandler 자동 저장 격리
 */
@Slf4j
@TestContainerSpringBootTest
class AttendRetrySchedulerTest {

    @Autowired
    private AttendRetryScheduler attendRetryScheduler;

    @Autowired
    private AttendRepository attendRepository;

    @MockitoSpyBean
    private AttendRedisService attendRedisService;

    @MockitoBean
    private AttendService attendService;

    @MockitoBean
    private EventStore eventStore;

    @BeforeEach
    void setUp() {
        attendRedisService.deleteAllKeys();
        clearInvocations(attendRedisService, attendService);
    }

    @Test
    @DisplayName("실패 큐 이벤트 재처리 성공 - DB 저장, Redis 참석자 반영, 큐에서 제거")
    void retry_Success() {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        attendRedisService.pushFailedEvent(userId, conferenceId, sessionId);

        attendRetryScheduler.retryFailedAttendEvents();

        verify(attendService).registerAttend(userId, conferenceId, sessionId);
        verify(attendRedisService).saveUserAttendance(conferenceId, sessionId, userId);
        assertThat(attendRedisService.getFailedEvents(10)).isEmpty();
        assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("sessionId가 null인 실패 이벤트 - conferenceId만으로 참석 재처리")
    void retry_NullSessionId_Success() {
        Long userId = 1L, conferenceId = 1L;
        attendRedisService.pushFailedEvent(userId, conferenceId, null);

        attendRetryScheduler.retryFailedAttendEvents();

        verify(attendService).registerAttend(userId, conferenceId, null);
        verify(attendRedisService).saveUserAttendance(conferenceId, null, userId);
        assertThat(attendRedisService.getFailedEvents(10)).isEmpty();
        assertThat(attendRedisService.attendCount(conferenceId, null)).isEqualTo(1L);
    }

    @Test
    @DisplayName("여러 실패 이벤트를 한 번에 재처리 - 모두 성공 시 큐 전체 비워짐")
    void retry_MultipleEvents_AllSuccess() {
        attendRedisService.pushFailedEvent(1L, 10L, 1L);
        attendRedisService.pushFailedEvent(2L, 10L, 1L);
        attendRedisService.pushFailedEvent(3L, 10L, 2L);

        attendRetryScheduler.retryFailedAttendEvents();

        verify(attendService, times(3)).registerAttend(any(), any(), any());
        verify(attendRedisService, times(3)).saveUserAttendance(any(), any(), any());
        assertThat(attendRedisService.getFailedEvents(10)).isEmpty();
    }

    @Test
    @DisplayName("실패 큐가 비어있으면 아무것도 처리하지 않음")
    void retry_EmptyQueue_DoesNothing() {
        attendRetryScheduler.retryFailedAttendEvents();

        verify(attendService, never()).registerAttend(any(), any(), any());
        verify(attendRedisService, never()).saveUserAttendance(any(), any(), any());
    }

    @Test
    @DisplayName("DB 저장 실패 시 이벤트가 큐에 그대로 유지됨 - 다음 주기에 재시도 가능")
    void retry_WhenDbFails_EventRemainsInQueue() {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;
        attendRedisService.pushFailedEvent(userId, conferenceId, sessionId);
        doThrow(new RuntimeException("DB 오류")).when(attendService).registerAttend(userId, conferenceId, sessionId);

        attendRetryScheduler.retryFailedAttendEvents();

        assertThat(attendRedisService.getFailedEvents(10)).hasSize(1);
        verify(attendRedisService, never()).removeFailedEvent(any());
    }

    @Test
    @DisplayName("일부 이벤트만 실패 시 성공한 이벤트만 큐에서 제거됨")
    void retry_PartialFailure_OnlyFailedEventRemainsInQueue() {
        Long successUserId = 1L, failUserId = 2L, conferenceId = 1L, sessionId = 1L;
        // leftPush이므로 나중에 push된 항목이 먼저 처리됨
        attendRedisService.pushFailedEvent(successUserId, conferenceId, sessionId); // 나중에 처리
        attendRedisService.pushFailedEvent(failUserId, conferenceId, sessionId);    // 먼저 처리
        doThrow(new RuntimeException("DB 오류")).when(attendService).registerAttend(failUserId, conferenceId, sessionId);

        attendRetryScheduler.retryFailedAttendEvents();

        List<String> remaining = attendRedisService.getFailedEvents(10);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0)).startsWith(failUserId + ":");
    }

    @Test
    @DisplayName("DB 실패로 Redis 롤백 후 재시도 - 참석자 수가 최종 1로 정확히 반영됨")
    void retry_AfterRollback_RedisCountCorrectlyRestored() {
        Long userId = 1L, conferenceId = 1L, sessionId = 2L;

        // 1단계: saveUserAttendance → count=1, 참석 set에 userId 추가
        attendRedisService.saveUserAttendance(conferenceId, sessionId, userId);
        assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(1L);

        // 2단계: DB 저장 실패 → rollbackAttendance → count=0, 참석 set에서 userId 제거
        attendRedisService.rollbackAttendance(conferenceId, sessionId, userId);
        assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(0L);

        // 3단계: 실패 이벤트 큐에 적재
        attendRedisService.pushFailedEvent(userId, conferenceId, sessionId);

        // 4단계: 스케줄러 재처리 → DB 저장 + Redis 재반영
        attendRetryScheduler.retryFailedAttendEvents();

        // 최종 검증
        verify(attendService).registerAttend(userId, conferenceId, sessionId);
        assertThat(attendRedisService.attendCount(conferenceId, sessionId)).isEqualTo(1L);
        assertThat(attendRedisService.getFailedEvents(10)).isEmpty();
    }
}
