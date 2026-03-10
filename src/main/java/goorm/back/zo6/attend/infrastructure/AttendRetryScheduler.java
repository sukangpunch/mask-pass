package goorm.back.zo6.attend.infrastructure;

import goorm.back.zo6.attend.application.AttendService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DB 저장 실패로 Redis 큐(failed:attend:events)에 쌓인 이벤트를 주기적으로 재처리한다.
 *
 * 재처리 성공: DB 저장 + Redis 참석자 재반영 + 큐에서 제거
 * 재처리 실패: 큐에 그대로 유지 → 다음 주기에 재시도
 */

@Slf4j
@RequiredArgsConstructor
@Component
public class AttendRetryScheduler {

    private static final int BATCH_SIZE = 50;

    private final AttendRedisService attendRedisService;
    private final AttendService attendService;

    @Scheduled(fixedDelay = 60_000) // 1분마다 실행
    public void retryFailedAttendEvents() {
        List<String> failedEvents = attendRedisService.getFailedEvents(BATCH_SIZE);
        if (failedEvents.isEmpty()) return;

        log.info("[재시도 스케줄러] 실패 이벤트 {}건 재처리 시작", failedEvents.size());

        for (String payload : failedEvents) {
            processFailedEvent(payload);
        }
    }

    private void processFailedEvent(String payload) {
        try {
            String[] parts = payload.split(":");
            Long userId = Long.parseLong(parts[0]);
            Long conferenceId = Long.parseLong(parts[1]);
            Long sessionId = "null".equals(parts[2]) ? null : Long.parseLong(parts[2]);

            attendService.registerAttend(userId, conferenceId, sessionId);
            attendRedisService.saveUserAttendance(conferenceId, sessionId, userId);
            attendRedisService.removeFailedEvent(payload);

            log.info("[재시도 성공] userId={}, conferenceId={}, sessionId={}", userId, conferenceId, sessionId);
        } catch (Exception e) {
            log.warn("[재시도 실패] payload={}, error={}. 다음 주기에 재시도.", payload, e.getMessage());
        }
    }
}
