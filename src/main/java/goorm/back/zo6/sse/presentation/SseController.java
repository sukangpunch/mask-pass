package goorm.back.zo6.sse.presentation;

import goorm.back.zo6.sse.application.SseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Tag(name = "sse", description = "Sse API")
@RestController
@RequestMapping("/api/v1/sse")
@RequiredArgsConstructor
public class SseController {

    private final SseService sseService;

    @Operation(summary = "얼굴 인식 SSE 연결", description = "각 구역별 기기의 참석률 제공을 위해 SSE 연결을 시도합니다.")
    @GetMapping(value = "/subscribe",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam("conferenceId") Long conferenceId,
                                @RequestParam(value = "sessionId", required = false) Long sessionId){
        return sseService.subscribe(conferenceId, sessionId);
    }

    @DeleteMapping("/unsubscribe")
    @Operation(summary = "얼굴 인식 SSE 연결 끊기", description = "SSE 연결이 제대로 끊어지지 않는 경우 서버 메모리를 위해 연결을 강제로 끊습니다.")
    public ResponseEntity<Void> unsubscribe(@RequestParam("conferenceId") Long conferenceId,
                                            @RequestParam(value = "sessionId", required = false) Long sessionId) {
        sseService.unsubscribe(conferenceId,sessionId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/last-count")
    @Operation(summary = "마지막 카운트 값 전부 삭제", description = "데이터 일관성을 위해, 마지막 count 값을 저장하여 관리하는 저장소를 비워줍니다.")
    public ResponseEntity<Void> clearLastKnownCounts() {
        sseService.clearLastKnownCounts();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    @Operation(summary = "[디버그] SSE 내부 상태 조회", description = "emitter 수 및 lastKnownCounts 크기를 반환합니다. 버그 재연·모니터링 전용입니다.")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(sseService.getStatus());
    }

}
