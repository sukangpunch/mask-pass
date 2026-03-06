package goorm.back.zo6.sse.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.sse.infrastructure.EmitterRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@RequiredArgsConstructor
@Service
public class SseService {

    private final EmitterRepository emitterRepository;

    private final Map<String, Long> lastKnownCounts = new ConcurrentHashMap<>();

    // [수정 1] TIMEOUT을 -1(무제한)에서 30분으로 복구한다.
    // -1로 설정하면 onTimeout() 콜백이 영원히 호출되지 않아
    // heartbeat에서 IOException이 발생하지 않는 한 emitter가 Map에 영구 잔류한다.
    private static final long TIMEOUT = 1000 * 1800L; // 30분
    private static final long RECONNECTION_TIMEOUT = 1000L;
    private static final String ATTEND_EVENT_NAME = "AttendanceCount";

    public SseEmitter subscribe(Long conferenceId, Long sessionId, String userId) {
        String baseKey = generateBaseKey(conferenceId, sessionId);

        // [수정 2] 기존 emitter 정리 시 onCompletion() 콜백 중복 호출 방지
        // 기존 코드: complete() → onCompletion() → deleteByKey() 후 다시 deleteByKey() 호출
        // 수정: 먼저 repository에서 제거한 뒤 complete()를 호출한다.
        // onCompletion() 콜백이 실행돼도 이미 삭제된 키이므로 중복 삭제가 무해하게 처리된다.
        Map<String, SseEmitter> existingEmitters = emitterRepository.findEmittersByBaseKey(baseKey);
        SseEmitter existingEmitter = existingEmitters.get(userId);
        if (existingEmitter != null) {
            emitterRepository.deleteByKey(baseKey, userId); // 먼저 삭제
            try {
                existingEmitter.complete(); // 이후 complete (onCompletion 콜백은 이미 삭제된 키 처리)
            } catch (Exception e) {
                log.warn("기존 emitter complete() 중 예외 (무시 가능): baseKey={}, userId={}, error={}", baseKey, userId, e.getMessage());
            }
            log.info("기존 SSE 연결 정리 완료: baseKey={}, userId={}", baseKey, userId);
        }

        SseEmitter sseEmitter = emitterRepository.save(baseKey, userId, new SseEmitter(TIMEOUT));
        registerEmitterHandlers(baseKey, userId, sseEmitter);
        sendInitialEvent(baseKey, sseEmitter);

        log.info("SSE 구독 완료: baseKey={}, userId={}, 전체 Emitter 수={}", baseKey, userId, emitterRepository.countEmitters());
        return sseEmitter;
    }

    public void sendAttendanceCount(Long conferenceId, Long sessionId, long count) {
        String baseKey = generateBaseKey(conferenceId, sessionId);
        lastKnownCounts.put(baseKey, count);

        Map<String, SseEmitter> targetEmitters = emitterRepository.findEmittersByBaseKey(baseKey);
        if (targetEmitters.isEmpty()) {
            return;
        }

        SseEmitter.SseEventBuilder event = buildAttendEvent(count);

        // [수정 3] forEach 순회 중 Map 수정 방지
        // 실패한 emitter를 즉시 삭제하면 ConcurrentHashMap 내부 버킷 구조가 변경되어
        // 동일 forEach 루프에서 예기치 않은 동작이 발생할 수 있다.
        // 삭제 대상을 별도 수집 후 순회 종료 뒤 일괄 삭제한다.
        List<String> failedUserIds = new ArrayList<>();

        targetEmitters.forEach((userId, emitter) -> {
            try {
                emitter.send(event);
            } catch (IOException e) {
                log.error("참석자 수 전송 실패: baseKey={}, userId={}, error={}", baseKey, userId, e.getMessage());
                failedUserIds.add(userId);
            }
        });

        failedUserIds.forEach(userId -> {
            SseEmitter failedEmitter = targetEmitters.get(userId);
            if (failedEmitter != null) {
                failedEmitter.complete();
            }
            emitterRepository.deleteByKey(baseKey, userId);
        });
    }

    public void unsubscribe(Long conferenceId, Long sessionId, String userId) {
        String baseKey = generateBaseKey(conferenceId, sessionId);
        Map<String, SseEmitter> userEmitters = emitterRepository.findEmittersByBaseKey(baseKey);
        SseEmitter emitter = userEmitters.get(userId);

        // [수정 2와 동일한 이유] 먼저 repository에서 제거 후 complete()
        emitterRepository.deleteByKey(baseKey, userId);
        if (emitter != null) {
            try {
                // 1. 강제 타임아웃 예외를 던져서 소켓을 즉각적으로 파괴합니다.
                // 이렇게 하면 Spring/Tomcat이 스케줄링을 기다리지 않고 연결을 즉시 CLOSE 합니다.
                emitter.completeWithError(new RuntimeException("클라이언트 요청에 의한 명시적 SSE 연결 종료"));            } catch (Exception e) {
                log.warn("unsubscribe complete() 중 예외 (무시 가능): baseKey={}, userId={}, error={}", baseKey, userId, e.getMessage());
            }
        }
        log.info("SSE 연결 종료 요청: baseKey={}, userId={}", baseKey, userId);
    }

    @Scheduled(fixedRate = 45_000)
    public void sendHeartbeat() {
        Map<String, Map<String, SseEmitter>> allEmitters = emitterRepository.findAllEmitters();

        // [수정 3과 동일] 삭제 대상 수집 후 일괄 처리
        List<String[]> failedKeys = new ArrayList<>(); // [baseKey, userId]

        allEmitters.forEach((baseKey, userEmitters) ->
                                    userEmitters.forEach((userId, emitter) -> {
                                        try {
                                            emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                                        } catch (IOException e) {
                                            log.info("Heartbeat 전송 실패 — 좀비 소켓 정리: baseKey={}, userId={}", baseKey, userId);
                                            failedKeys.add(new String[]{baseKey, userId});
                                        }
                                    })
        );

        failedKeys.forEach(key -> {
            String baseKey = key[0];
            String userId = key[1];
            Map<String, SseEmitter> userEmitters = allEmitters.get(baseKey);
            if (userEmitters != null) {
                SseEmitter failedEmitter = userEmitters.get(userId);
                if (failedEmitter != null) {
                    failedEmitter.complete();
                }
            }
            emitterRepository.deleteByKey(baseKey, userId);
        });
    }

    public void clearLastKnownCounts() {
        lastKnownCounts.clear();
        log.info("lastKnownCounts 저장소 초기화 완료");
    }

    public long incrementCount(Long conferenceId, Long sessionId) {
        String baseKey = generateBaseKey(conferenceId, sessionId);
        long newCount = lastKnownCounts.merge(baseKey, 1L, Long::sum);
        sendAttendanceCount(conferenceId, sessionId, newCount);
        log.info("[테스트] 카운트 증가: baseKey={}, newCount={}", baseKey, newCount);
        return newCount;
    }

    public Map<String, Object> getStatus() {
        return Map.of(
                "emitterCount", emitterRepository.countEmitters(),
                "lastKnownCountsSize", lastKnownCounts.size(),
                "lastKnownCountsKeys", lastKnownCounts.keySet()
        );
    }

    // --- private helpers ---

    private void sendInitialEvent(String baseKey, SseEmitter sseEmitter) {
        lastKnownCounts.putIfAbsent(baseKey, 0L);
        long baseAttendCount = lastKnownCounts.getOrDefault(baseKey, 0L);
        SseEmitter.SseEventBuilder event = buildAttendEvent(baseAttendCount);
        try {
            sseEmitter.send(event);
        } catch (IOException e) {
            log.error("초기 이벤트 전송 실패: baseKey={}, error={}", baseKey, e.getMessage());
            throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
        }
    }

    private void registerEmitterHandlers(String baseKey, String userId, SseEmitter sseEmitter) {
        sseEmitter.onCompletion(() -> {
            log.info("SSE 연결 완료(종료): baseKey={}, userId={}", baseKey, userId);
            emitterRepository.deleteByKey(baseKey, userId);
        });

        sseEmitter.onTimeout(() -> {
            log.info("SSE 연결 타임아웃: baseKey={}, userId={}", baseKey, userId);
            // [참고] onTimeout()은 SseEmitter 내부에서 complete()를 자동 호출하므로
            // 여기서 별도로 complete()를 호출할 필요 없다.
            // 단, Spring 버전에 따라 동작이 다를 수 있으므로 deleteByKey()만 명시적으로 처리한다.
            emitterRepository.deleteByKey(baseKey, userId);
        });

        sseEmitter.onError(e -> {
            log.info("SSE 연결 오류: baseKey={}, userId={}, error={}", baseKey, userId, e.getMessage());
            emitterRepository.deleteByKey(baseKey, userId);
        });
    }

    private SseEmitter.SseEventBuilder buildAttendEvent(Object data) {
        return SseEmitter.event()
                .name(ATTEND_EVENT_NAME)
                .data(data)
                .reconnectTime(RECONNECTION_TIMEOUT);
    }

    private String generateBaseKey(Long conferenceId, Long sessionId) {
        if (conferenceId == null) {
            throw new CustomException(ErrorCode.MISSING_REQUIRED_PARAMETER);
        }
        if (sessionId == null) {
            return "conference:" + conferenceId;
        }
        return "conference:" + conferenceId + ":session:" + sessionId;
    }
}
