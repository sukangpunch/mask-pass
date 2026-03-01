package goorm.back.zo6.sse.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.sse.infrastructure.EmitterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
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
    private final Map<String, Long> lastKnownCounts = new ConcurrentHashMap<>();
    private static final long TIMEOUT = 1800*1000L;
    private static final long RECONNECTION_TIMEOUT = 1000L;
    private static final String ATTEND_EVENT_NAME = "AttendanceCount";

    public SseEmitter subscribe(Long conferenceId, Long sessionId){
        String eventKey = generateEventKey(conferenceId, sessionId);
        SseEmitter newEmitter = new SseEmitter(TIMEOUT);

        // 원자적으로 교체: 기존 emitter가 있으면 반환, 없으면 null
        // 이전 코드의 find→check→delete→save 4단계는 레이스 컨디션 발생 가능
        SseEmitter oldEmitter = emitterRepository.getAndReplace(eventKey, newEmitter);
        if (oldEmitter != null) {
            try {
                oldEmitter.send(SseEmitter.event().name("check").data("ping"));
                log.info("기존 SSE 연결이 유지 중이었음, 교체: {}", eventKey);
            } catch (IOException e) {
                log.info("기존 SSE 연결이 끊김 (CLOSE_WAIT 추정), 교체: {}", eventKey);
            }
            oldEmitter.complete(); // 교체된 기존 emitter 정리
        }

        registerEmitterHandler(eventKey, newEmitter);

        sendToClientFirst(eventKey, newEmitter);

        log.info("===  SSE Emitter 개수: {} ===", emitterRepository.countEmitters());
        return newEmitter;
    }

    // SseEmitter 를 통해 클라이언트에게 초기 전달용 이벤트를 전송하는 역할을 합니다.
    private void sendToClientFirst(String eventKey, SseEmitter sseEmitter){
        lastKnownCounts.putIfAbsent(eventKey, 0L);
        long baseAttendCount = lastKnownCounts.getOrDefault(eventKey, 0L);
        SseEmitter.SseEventBuilder event = getSseAttendEvent(eventKey, baseAttendCount);
        try{
            sseEmitter.send(event);
        }catch (IOException e){
            log.error("구독 실패, eventId ={}, {}", eventKey, e.getMessage());
            throw new CustomException(ErrorCode.SSE_CONNECTION_FAILED);
        }
    }

    // 기기에 해당하는 참석자 수 리턴
    public void sendAttendanceCount(Long conferenceId, Long sessionId, long count){
        String eventKey = generateEventKey(conferenceId, sessionId);
        lastKnownCounts.put(eventKey, count);
        SseEmitter emitter = emitterRepository.findEmitterByKey(eventKey);
        SseEmitter.SseEventBuilder event = getSseAttendEvent(eventKey, count);
        if(emitter != null){
            try{
                emitter.send(event);
            } catch (IOException e) {
                // @Async 쓰레드에서 예외를 throw해도 호출부로 전파되지 않고 무시됨
                // 대신 끊긴 emitter를 제거하고 lastKnownCounts도 정리
                log.warn("SSE 전송 실패(연결 끊김), emitter 제거: eventId={}, {}", eventKey, e.getMessage());
                emitterRepository.deleteByEventKey(eventKey);
                lastKnownCounts.remove(eventKey);
            }
        }
    }

    // 이벤트 id와, data 를 이용해서 SSE 이벤트 객체를 생성합니다.
    private SseEmitter.SseEventBuilder getSseAttendEvent(String eventKey, Object data){
        return SseEmitter.event()
                .id(eventKey)
                .name(ATTEND_EVENT_NAME)
                .data(data)
                .reconnectTime(RECONNECTION_TIMEOUT); // 클라이언트와 연결이 끊겼을 때 클라이언트가 서버와 재연결을 시도하기 전에 대기할 시간
        // 해당 기능은 라이언트가 재연결 시도를 할 수 있도록 브라우저에게 힌트를 주는 역할
    }

    // SSE 연결이 종료되거나, 타임아웃되거나, 오류가 발생할 때 적절한 처리를 수행하도록 Emitter에 핸들러를 등록
    void registerEmitterHandler(String eventId, SseEmitter sseEmitter){
        /*  SSE 연결이 정상적으로 종료되었을 때
           - 사용자가 브라우저를 닫거나 SSE 구독을 취소할 때
           - 서버에서 sseEmitter.complete()을 호출했을 때
        */
        sseEmitter.onCompletion(()->{
            log.info("연결이 끝났습니다. : eventId = {}", eventId);
            emitterRepository.deleteByEventKey(eventId);
            lastKnownCounts.remove(eventId); // 메모리 누수 방지: emitter 종료 시 lastKnownCounts 정리
        });

        /*  SSE 연결이 타임아웃되었을 때
           - 클라이언트가 네트워크 문제로 오랜 시간 동안 응답을 받지 못했을 때
           - 서버에서 일정 시간 동안 데이터가 전송되지 않아 클라이언트가 반응하지 않을 때
           - SseEmitter가 설정된 timeout이 초과되었을 때
        */
        sseEmitter.onTimeout(()->{
            log.info("Timeout이 발생했습니다. : eventId={}", eventId);
            emitterRepository.deleteByEventKey(eventId);
            lastKnownCounts.remove(eventId); // 메모리 누수 방지
        });

        /*  SSE 연결 중 에러가 발생했을 때
            - 클라이언트가 갑자기 연결을 끊었을 때 (ERR_INCOMPLETE_CHUNKED_ENCODING)
            - 서버에서 SSE 메시지를 전송하는 중 네트워크 장애가 발생했을 때
            - 잘못된 데이터 형식이 전송되어 클라이언트가 파싱하지 못할 때
         */
        sseEmitter.onError((e) ->{
            log.info("에러가 발생했습니다. error={}, eventId={}", e.getMessage(),eventId);
            emitterRepository.deleteByEventKey(eventId);
            lastKnownCounts.remove(eventId); // 메모리 누수 방지
        });
    }

    private String generateEventKey(Long conferenceId, Long sessionId){
        if(conferenceId == null){
            throw new CustomException(ErrorCode.MISSING_REQUIRED_PARAMETER);
        }

        if(sessionId == null){
            return "conference:" + conferenceId;
        }else{
            return "conference:" + conferenceId + ":session:" + sessionId;
        }
    }

    public void  unsubscribe(Long conferenceId, Long sessionId) {
        String eventKey = generateEventKey(conferenceId, sessionId);
        SseEmitter emitter = emitterRepository.findEmitterByKey(eventKey);
        if (emitter != null) {
            emitter.complete(); // 소켓 연결 종료
        }
        emitterRepository.deleteByEventKey(eventKey);
        log.info("SSE 연결 종료 요청: eventKey = {}", eventKey);
    }

    public void clearLastKnownCounts(){
        lastKnownCounts.clear();
        log.info("LastKnownCounts Map 저장소 정보 초기화");
    }

    /** 재연·모니터링용: 현재 내부 상태 스냅샷 */
    public Map<String, Object> getStatus() {
        return Map.of(
            "emitterCount", emitterRepository.countEmitters(),
            "lastKnownCountsSize", lastKnownCounts.size(),
            "lastKnownCountsKeys", lastKnownCounts.keySet()
        );
    }

}
