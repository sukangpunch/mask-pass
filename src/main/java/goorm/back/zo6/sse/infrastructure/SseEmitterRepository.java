package goorm.back.zo6.sse.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 회의/세션별 SSE Emitter 저장소.
 *
 * 구조: baseKey("conference:{id}" 또는 "conference:{id}:session:{id}")
 *        -> userId -> SseEmitter
 *
 * 같은 회의에 접속하는 복수의 기기(사용자)가 서로의 연결을 끊지 않도록
 * baseKey + userId 조합으로 Emitter를 분리하여 관리한다.
 */
@Repository
@RequiredArgsConstructor
public class SseEmitterRepository implements EmitterRepository {

    /**
     * baseKey -> (userId -> SseEmitter)
     * 동일 baseKey 하에 여러 사용자의 Emitter를 독립적으로 보관한다.
     */
    private final Map<String, Map<String, SseEmitter>> emitters = new ConcurrentHashMap<>();

    @Override
    public SseEmitter save(String baseKey, String userId, SseEmitter sseEmitter) {
        emitters.computeIfAbsent(baseKey, k -> new ConcurrentHashMap<>())
                .put(userId, sseEmitter);
        return sseEmitter;
    }

    @Override
    public void deleteByKey(String baseKey, String userId) {
        Map<String, SseEmitter> userEmitters = emitters.get(baseKey);
        if (userEmitters == null) {
            return;
        }
        userEmitters.remove(userId);
        if (userEmitters.isEmpty()) {
            emitters.remove(baseKey);
        }
    }

    /**
     * 특정 회의/세션에 연결된 모든 사용자의 Emitter를 반환한다.
     * 반환된 Map은 수정 불가 뷰이므로 읽기 전용으로 사용해야 한다.
     */
    @Override
    public Map<String, SseEmitter> findEmittersByBaseKey(String baseKey) {
        Map<String, SseEmitter> userEmitters = emitters.get(baseKey);
        if (userEmitters == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(userEmitters);
    }

    /**
     * 전체 Emitter를 반환한다. Heartbeat 전송에 사용된다.
     */
    @Override
    public Map<String, Map<String, SseEmitter>> findAllEmitters() {
        return Collections.unmodifiableMap(emitters);
    }

    @Override
    public int countEmitters() {
        return emitters.values().stream()
                .mapToInt(Map::size)
                .sum();
    }
}
