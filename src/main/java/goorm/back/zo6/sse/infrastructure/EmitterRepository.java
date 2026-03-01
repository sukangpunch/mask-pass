package goorm.back.zo6.sse.infrastructure;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface EmitterRepository {
    SseEmitter save(String eventKey, SseEmitter sseEmitter);
    void deleteByEventKey(String eventKey);
    SseEmitter findEmitterByKey(String key);
    /** 기존 emitter를 새 emitter로 원자적으로 교체하고, 이전 emitter(없으면 null)를 반환 */
    SseEmitter getAndReplace(String eventKey, SseEmitter newEmitter);
    int countEmitters();
}
