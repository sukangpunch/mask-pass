package goorm.back.zo6.sse.infrastructure;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

public interface EmitterRepository {
    SseEmitter save(String baseKey, String userId, SseEmitter sseEmitter);
    void deleteByKey(String baseKey, String userId);
    Map<String, SseEmitter> findEmittersByBaseKey(String baseKey);
    Map<String, Map<String, SseEmitter>> findAllEmitters();
    int countEmitters();
}
