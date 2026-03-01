package goorm.back.zo6.sse.infrastructure;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@RequiredArgsConstructor
public class SseEmitterRepository implements EmitterRepository {
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void registerMetrics() {
        Gauge.builder("sse.emitter.count", emitters, Map::size)
                .description("현재 활성 SSE Emitter 수")
                .register(meterRegistry);
    }

    @Override
    public SseEmitter save(String eventKey, SseEmitter sseEmitter) {
        emitters.put(eventKey, sseEmitter);
        return sseEmitter;
    }

    @Override
    public void deleteByEventKey(String eventKey) {
        emitters.remove(eventKey);
    }

    @Override
    public SseEmitter getAndReplace(String eventKey, SseEmitter newEmitter) {
        return emitters.put(eventKey, newEmitter); // ConcurrentHashMap.put은 원자적으로 교체하고 이전 값을 반환
    }

    @Override
    public SseEmitter findEmitterByKey(String eventKey) {
        return emitters.get(eventKey);
    }

    public int countEmitters() {
        return emitters.size();
    }
}
