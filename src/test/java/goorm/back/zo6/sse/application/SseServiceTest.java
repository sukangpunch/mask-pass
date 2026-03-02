package goorm.back.zo6.sse.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.sse.infrastructure.EmitterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SseServiceTest {

    @InjectMocks
    private SseService sseService;

    @Mock
    private EmitterRepository emitterRepository;

    private static final long TIMEOUT = 1800 * 1000L;
    private static final String USER_ID = "device-uuid-001";

    @Test
    @DisplayName("sse 연결 - 컨퍼런스 기기 sse 연결 성공")
    void subscribe_ConferenceSuccess() {
        // given
        Long conferenceId = 1L;
        Long sessionId = null;
        String baseKey = "conference:1";
        SseEmitter mockEmitter = new SseEmitter(TIMEOUT);

        when(emitterRepository.findEmittersByBaseKey(baseKey)).thenReturn(Collections.emptyMap());
        when(emitterRepository.save(eq(baseKey), eq(USER_ID), any(SseEmitter.class))).thenReturn(mockEmitter);

        // when
        SseEmitter sseEmitter = sseService.subscribe(conferenceId, sessionId, USER_ID);

        // then
        assertNotNull(sseEmitter);
        verify(emitterRepository, times(1)).save(eq(baseKey), eq(USER_ID), any(SseEmitter.class));
    }

    @Test
    @DisplayName("sse 연결 - 세션 기기 sse 연결 성공")
    void subscribe_SessionSuccess() {
        // given
        Long conferenceId = 1L;
        Long sessionId = 2L;
        String baseKey = "conference:1:session:2";
        SseEmitter mockEmitter = new SseEmitter(TIMEOUT);

        when(emitterRepository.findEmittersByBaseKey(baseKey)).thenReturn(Collections.emptyMap());
        when(emitterRepository.save(eq(baseKey), eq(USER_ID), any(SseEmitter.class))).thenReturn(mockEmitter);

        // when
        SseEmitter sseEmitter = sseService.subscribe(conferenceId, sessionId, USER_ID);

        // then
        assertNotNull(sseEmitter);
        verify(emitterRepository, times(1)).save(eq(baseKey), eq(USER_ID), any(SseEmitter.class));
    }

    @Test
    @DisplayName("sse 연결 - conferenceId 가 null 이면 연결 실패")
    void subscribe_NoneConferenceFails() {
        // given
        Long conferenceId = null;
        Long sessionId = 2L;

        // when & then
        CustomException exception = assertThrows(CustomException.class,
                () -> sseService.subscribe(conferenceId, sessionId, USER_ID));
        assertEquals(ErrorCode.MISSING_REQUIRED_PARAMETER, exception.getErrorCode());
        verifyNoInteractions(emitterRepository);
    }

    @Test
    @DisplayName("sse 연결 - 동일 userId로 재연결 시 기존 Emitter를 교체한다")
    void subscribe_ReplacesExistingEmitter() throws IOException {
        // given
        Long conferenceId = 1L;
        Long sessionId = null;
        String baseKey = "conference:1";
        SseEmitter existingEmitter = mock(SseEmitter.class);
        SseEmitter newEmitter = new SseEmitter(TIMEOUT);

        when(emitterRepository.findEmittersByBaseKey(baseKey))
                .thenReturn(Map.of(USER_ID, existingEmitter));
        when(emitterRepository.save(eq(baseKey), eq(USER_ID), any(SseEmitter.class))).thenReturn(newEmitter);

        // when
        SseEmitter result = sseService.subscribe(conferenceId, sessionId, USER_ID);

        // then
        assertNotNull(result);
        verify(existingEmitter, times(1)).complete();
        verify(emitterRepository, times(1)).deleteByKey(baseKey, USER_ID);
        verify(emitterRepository, times(1)).save(eq(baseKey), eq(USER_ID), any(SseEmitter.class));
    }

    @Test
    @DisplayName("sse 연결 - 서로 다른 userId는 서로의 연결을 끊지 않는다")
    void subscribe_DifferentUsersDontEvictEachOther() {
        // given
        Long conferenceId = 1L;
        Long sessionId = null;
        String baseKey = "conference:1";
        String userA = "device-uuid-A";
        String userB = "device-uuid-B";
        SseEmitter emitterA = new SseEmitter(TIMEOUT);
        SseEmitter emitterB = new SseEmitter(TIMEOUT);

        // userA의 기존 emitter가 없는 상태에서 userB가 접속해도 아무 emitter도 끊기지 않아야 한다
        when(emitterRepository.findEmittersByBaseKey(baseKey)).thenReturn(Map.of(userA, emitterA));
        when(emitterRepository.save(eq(baseKey), eq(userB), any(SseEmitter.class))).thenReturn(emitterB);

        // when
        SseEmitter result = sseService.subscribe(conferenceId, sessionId, userB);

        // then
        assertNotNull(result);
        // userA의 emitter에 대한 complete()나 deleteByKey()가 호출되지 않아야 한다
        verify(emitterRepository, never()).deleteByKey(baseKey, userA);
    }

    @Test
    @DisplayName("실시간 참석자 수 count 전송 - 컨퍼런스 참석 count 전송 성공")
    void sendAttendanceCount_ConferenceSuccess() throws IOException {
        // given
        Long conferenceId = 1L;
        Long sessionId = null;
        long count = 10;
        String baseKey = "conference:1";
        SseEmitter mockEmitter = mock(SseEmitter.class);

        when(emitterRepository.findEmittersByBaseKey(baseKey)).thenReturn(Map.of(USER_ID, mockEmitter));

        // when
        sseService.sendAttendanceCount(conferenceId, sessionId, count);

        // then
        verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("실시간 참석자 수 count 전송 - 세션 참석 count 전송 성공")
    void sendAttendanceCount_SessionSuccess() throws IOException {
        // given
        Long conferenceId = 1L;
        Long sessionId = 2L;
        long count = 10;
        String baseKey = "conference:1:session:2";
        SseEmitter mockEmitter = mock(SseEmitter.class);

        when(emitterRepository.findEmittersByBaseKey(baseKey)).thenReturn(Map.of(USER_ID, mockEmitter));

        // when
        sseService.sendAttendanceCount(conferenceId, sessionId, count);

        // then
        verify(mockEmitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("실시간 참석자 수 count 전송 - 해당 baseKey에 Emitter가 없으면 아무 동작 없이 종료")
    void sendAttendanceCount_NoEmitter_DoesNothing() {
        // given
        Long conferenceId = 1L;
        Long sessionId = 2L;
        long count = 10;
        String baseKey = "conference:1:session:2";

        when(emitterRepository.findEmittersByBaseKey(baseKey)).thenReturn(Collections.emptyMap());

        // when & then
        assertDoesNotThrow(() -> sseService.sendAttendanceCount(conferenceId, sessionId, count));
        verify(emitterRepository, times(1)).findEmittersByBaseKey(baseKey);
        verifyNoMoreInteractions(emitterRepository);
    }

    @Test
    @DisplayName("실시간 참석자 수 count 전송 - IOException 발생 시 해당 Emitter를 정리하고 계속 진행한다")
    void sendAttendanceCount_IOExceptionCleansUpEmitter() throws IOException {
        // given
        Long conferenceId = 1L;
        Long sessionId = 2L;
        long count = 10;
        String baseKey = "conference:1:session:2";
        SseEmitter mockEmitter = mock(SseEmitter.class);

        when(emitterRepository.findEmittersByBaseKey(baseKey)).thenReturn(Map.of(USER_ID, mockEmitter));
        doThrow(new IOException("SSE 전송 실패")).when(mockEmitter).send(any(SseEmitter.SseEventBuilder.class));

        // when & then — IOException은 더 이상 CustomException으로 전파되지 않고 해당 emitter만 정리된다
        assertDoesNotThrow(() -> sseService.sendAttendanceCount(conferenceId, sessionId, count));
        verify(mockEmitter, times(1)).complete();
        verify(emitterRepository, times(1)).deleteByKey(baseKey, USER_ID);
    }

    @Test
    @DisplayName("실시간 참석자 수 count 전송 - conferenceId 가 null 이면 실패")
    void sendAttendanceCount_NoneConferenceFails() {
        // given
        Long conferenceId = null;
        Long sessionId = 2L;
        long count = 10;

        // when & then
        CustomException exception = assertThrows(CustomException.class,
                () -> sseService.sendAttendanceCount(conferenceId, sessionId, count));
        assertEquals(ErrorCode.MISSING_REQUIRED_PARAMETER, exception.getErrorCode());
        verifyNoInteractions(emitterRepository);
    }

    @Test
    @DisplayName("Heartbeat - 연결된 모든 Emitter에 ping 이벤트를 전송한다")
    void sendHeartbeat_SendsPingToAllEmitters() throws IOException {
        // given
        SseEmitter emitter1 = mock(SseEmitter.class);
        SseEmitter emitter2 = mock(SseEmitter.class);

        Map<String, Map<String, SseEmitter>> allEmitters = Map.of(
                "conference:1", Map.of("userA", emitter1),
                "conference:2", Map.of("userB", emitter2)
        );
        when(emitterRepository.findAllEmitters()).thenReturn(allEmitters);

        // when
        sseService.sendHeartbeat();

        // then
        verify(emitter1, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter2, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("Heartbeat - IOException 발생 시 해당 Emitter를 정리한다")
    void sendHeartbeat_CleansUpDeadEmitterOnIOException() throws IOException {
        // given
        SseEmitter deadEmitter = mock(SseEmitter.class);
        String baseKey = "conference:1";

        Map<String, Map<String, SseEmitter>> allEmitters = Map.of(
                baseKey, Map.of(USER_ID, deadEmitter)
        );
        when(emitterRepository.findAllEmitters()).thenReturn(allEmitters);
        doThrow(new IOException("broken pipe")).when(deadEmitter).send(any(SseEmitter.SseEventBuilder.class));

        // when
        sseService.sendHeartbeat();

        // then
        verify(deadEmitter, times(1)).complete();
        verify(emitterRepository, times(1)).deleteByKey(baseKey, USER_ID);
    }
}
