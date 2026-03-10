package goorm.back.zo6.attend.infrastructure;

import goorm.back.zo6.attend.application.AttendService;
import goorm.back.zo6.attend.domain.AttendEvent;
import goorm.back.zo6.attend.dto.AttendInfo;
import goorm.back.zo6.sse.application.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log4j2
public class AttendEventHandler {

    private final AttendRedisService attendRedisService;
    private final AttendService attendService;
    private final SseService sseService;

    @Async("customTaskExecutor")
    @EventListener(AttendEvent.class)
    public void handle(AttendEvent event) {
        Long conferenceId = event.getConferenceId();
        Long sessionId = event.getSessionId();
        Long userId = event.getUserId();

        AttendInfo attendInfo = attendRedisService.saveUserAttendance(conferenceId, sessionId, userId);

        // SSE는 best-effort 전송 — 실패해도 Redis/DB 처리에 영향 없도록 격리
        sendAttendanceCountSafely(conferenceId, sessionId, attendInfo.attendCount());

        if (attendInfo.isNewUser()) {
            try {
                attendService.registerAttend(userId, conferenceId, sessionId);
            } catch (Exception e) {
                log.error("[DB 저장 실패] Redis 롤백 후 실패 이벤트 큐 적재. userId={}, conferenceId={}, sessionId={}, error={}",
                        userId, conferenceId, sessionId, e.getMessage(), e);
                rollbackAndEnqueue(event);
            }
        }
    }

    private void rollbackAndEnqueue(AttendEvent event) {
        Long userId = event.getUserId();
        Long conferenceId = event.getConferenceId();
        Long sessionId = event.getSessionId();

        try {
            attendRedisService.rollbackAttendance(conferenceId, sessionId, userId);
        } catch (Exception e) {
            log.error("[CRITICAL] Redis 롤백 실패 — 수동 확인 필요. userId={}, conferenceId={}, sessionId={}, error={}",
                    userId, conferenceId, sessionId, e.getMessage(), e);
        }

        try {
            attendRedisService.pushFailedEvent(userId, conferenceId, sessionId);
        } catch (Exception e) {
            log.error("[CRITICAL] 실패 이벤트 큐 적재 실패 — 즉각 수동 확인 필요. userId={}, conferenceId={}, sessionId={}, error={}",
                    userId, conferenceId, sessionId, e.getMessage(), e);
        }
    }

    private void sendAttendanceCountSafely(Long conferenceId, Long sessionId, long count) {
        try {
            sseService.sendAttendanceCount(conferenceId, sessionId, count);
        } catch (Exception e) {
            log.warn("[SSE 전송 실패] Redis/DB 처리는 계속 진행. conferenceId={}, sessionId={}, error={}",
                    conferenceId, sessionId, e.getMessage());
        }
    }
}
