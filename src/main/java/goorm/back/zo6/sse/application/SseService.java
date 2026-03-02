package goorm.back.zo6.sse.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.sse.infrastructure.EmitterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
@Service
@Log4j2
public class SseService {

    private final EmitterRepository emitterRepository;

    /**
     * 마지막으로 전송한 참석자 수를 baseKey 단위로 보관한다.
     * 신규 구독자가 연결되었을 때 현재 카운트를 즉시 전달하는 데 사용된다.
     */
    private final Map<String, Long> lastKnownCounts = new ConcurrentHashMap<>();

    private static final long TIMEOUT = SseEmitter.UNDEFINED_TIMEOUT; // -1: 무제한, 좀비 소켓은 heartbeat(45s)로 감지
    private static final long RECONNECTION_TIMEOUT = 1000L;
    private static final String ATTEND_EVENT_NAME = "AttendanceCount";

    /**
     * SSE 구독을 처리한다.
     *
     * userId는 같은 회의/세션에 접속하는 복수의 기기(사용자)를 구분하는 고유 식별자다.
     * baseKey(회의/세션) + userId 조합으로 Emitter를 저장하므로,
     * 서로 다른 사용자의 연결이 서로를 끊어내는 현상이 발생하지 않는다.
     *
     * @param conferenceId 컨퍼런스 ID (필수)
     * @param sessionId    세션 ID (선택)
     * @param userId       구독 기기/사용자를 식별하는 고유 값
     */
    public SseEmitter subscribe(Long conferenceId, Long sessionId, String userId) {
        String baseKey = generateBaseKey(conferenceId, sessionId);

        // 동일 기기의 기존 연결이 있으면 정리한다 (재연결 시나리오)
        Map<String, SseEmitter> existingEmitters = emitterRepository.findEmittersByBaseKey(baseKey);
        SseEmitter existingEmitter = existingEmitters.get(userId);
        if (existingEmitter != null) {
            try {
                existingEmitter.send(SseEmitter.event().name("check").data("ping"));
                log.info("기존 SSE 연결 감지, 교체 진행: baseKey={}, userId={}", baseKey, userId);
                existingEmitter.complete();
            } catch (IOException e) {
                log.info("기존 SSE 연결이 이미 끊긴 상태 (CLOSE_WAIT 추정): baseKey={}, userId={}", baseKey, userId);
                existingEmitter.complete();
            }
            emitterRepository.deleteByKey(baseKey, userId);
        }

        SseEmitter sseEmitter = emitterRepository.save(baseKey, userId, new SseEmitter(TIMEOUT));
        registerEmitterHandlers(baseKey, userId, sseEmitter);
        sendInitialEvent(baseKey, sseEmitter);

        log.info("SSE 구독 완료: baseKey={}, userId={}, 전체 Emitter 수={}", baseKey, userId, emitterRepository.countEmitters());
        return sseEmitter;
    }

    /**
     * 특정 회의/세션의 참석자 수를 해당 baseKey에 연결된 모든 기기에 전송한다.
     *
     * @param conferenceId 컨퍼런스 ID
     * @param sessionId    세션 ID (선택)
     * @param count        전송할 참석자 수
     */
    public void sendAttendanceCount(Long conferenceId, Long sessionId, long count) {
        String baseKey = generateBaseKey(conferenceId, sessionId);
        lastKnownCounts.put(baseKey, count);

        Map<String, SseEmitter> targetEmitters = emitterRepository.findEmittersByBaseKey(baseKey);
        if (targetEmitters.isEmpty()) {
            return;
        }

        SseEmitter.SseEventBuilder event = buildAttendEvent(baseKey, count);
        targetEmitters.forEach((userId, emitter) -> {
            try {
                emitter.send(event);
            } catch (IOException e) {
                log.error("참석자 수 전송 실패: baseKey={}, userId={}, error={}", baseKey, userId, e.getMessage());
                emitter.complete();
                emitterRepository.deleteByKey(baseKey, userId);
            }
        });
    }

    /**
     * 특정 기기의 SSE 연결을 명시적으로 종료한다.
     *
     * @param conferenceId 컨퍼런스 ID
     * @param sessionId    세션 ID (선택)
     * @param userId       구독 기기/사용자를 식별하는 고유 값
     */
    public void unsubscribe(Long conferenceId, Long sessionId, String userId) {
        String baseKey = generateBaseKey(conferenceId, sessionId);
        Map<String, SseEmitter> userEmitters = emitterRepository.findEmittersByBaseKey(baseKey);
        SseEmitter emitter = userEmitters.get(userId);
        if (emitter != null) {
            emitter.complete();
        }
        emitterRepository.deleteByKey(baseKey, userId);
        log.info("SSE 연결 종료 요청: baseKey={}, userId={}", baseKey, userId);
    }

    /**
     * 45초마다 연결된 모든 Emitter에 heartbeat 이벤트를 전송한다.
     *
     * Nginx 등 중간 인프라가 유휴 연결을 임의로 끊는 것을 방지하고,
     * 이미 단절된 좀비 소켓을 능동적으로 감지해 정리한다.
     */
    @Scheduled(fixedRate = 45_000)
    public void sendHeartbeat() {
        Map<String, Map<String, SseEmitter>> allEmitters = emitterRepository.findAllEmitters();
        allEmitters.forEach((baseKey, userEmitters) ->
                userEmitters.forEach((userId, emitter) -> {
                    try {
                        emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
                    } catch (IOException e) {
                        log.info("Heartbeat 전송 실패 — 좀비 소켓 정리: baseKey={}, userId={}", baseKey, userId);
                        emitter.complete();
                        emitterRepository.deleteByKey(baseKey, userId);
                    }
                })
        );
    }

    public void clearLastKnownCounts() {
        lastKnownCounts.clear();
        log.info("lastKnownCounts 저장소 초기화 완료");
    }

    /** 재연·모니터링용: 현재 내부 상태 스냅샷 */
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
        SseEmitter.SseEventBuilder event = buildAttendEvent(baseKey, baseAttendCount);
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
            emitterRepository.deleteByKey(baseKey, userId);
        });

        sseEmitter.onError(e -> {
            log.info("SSE 연결 오류: baseKey={}, userId={}, error={}", baseKey, userId, e.getMessage());
            emitterRepository.deleteByKey(baseKey, userId);
        });
    }

    private SseEmitter.SseEventBuilder buildAttendEvent(String baseKey, Object data) {
        return SseEmitter.event()
                .id(baseKey)
                .name(ATTEND_EVENT_NAME)
                .data(data)
                .reconnectTime(RECONNECTION_TIMEOUT);
    }

    /**
     * 회의/세션의 베이스 키를 생성한다.
     * 이 키는 Emitter를 그룹화하는 데 사용되며, 사용자별 고유 식별은 호출부에서 userId로 처리한다.
     */
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
