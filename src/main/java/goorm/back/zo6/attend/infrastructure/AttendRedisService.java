package goorm.back.zo6.attend.infrastructure;

import goorm.back.zo6.attend.dto.AttendInfo;
import goorm.back.zo6.attend.dto.AttendKeys;
import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

@Log4j2
@RequiredArgsConstructor
@Service
public class AttendRedisService {

    private final RedisTemplate<String, String> redisTemplate;

    private static final String FAILED_ATTEND_KEY = "failed:attend:events";

    public long attendCount(Long conferenceId, Long sessionId) {
        AttendKeys attendKeys = generateKeys(conferenceId, sessionId);
        String countKey = attendKeys.countKey();
        String countStr = redisTemplate.opsForValue().get(countKey);
        return (countStr != null) ? Long.parseLong(countStr) : 0L;
    }

    // 참석 처리
    public AttendInfo saveUserAttendance(Long conferenceId, Long sessionId, Long userId) {
        if (conferenceId == null) {
            throw new CustomException(ErrorCode.MISSING_REQUIRED_PARAMETER);
        }

        AttendKeys keys = generateKeys(conferenceId, sessionId);

        return processAttendance(keys, userId);
    }

    private AttendInfo processAttendance(AttendKeys keys, Long userId) {
        log.info("{} 참가", keys.isSession() ? "Session" : "Conference");

        boolean isNewUser = addNewUserToAttendance(keys.attendanceKey(), userId);

        if (isNewUser) {
            incrementCountIfNew(keys.countKey());
        }

        long count = getCurrentCount(keys.countKey());

        return AttendInfo.of(isNewUser, count);
    }

    private boolean addNewUserToAttendance(String attendanceKey, Long userId) {
        Long added = redisTemplate.opsForSet().add(attendanceKey, userId.toString());
        if (added > 0) {
            expireAtNextDay5AM(attendanceKey);
            return true;
        }
        return false;
    }

    private void incrementCountIfNew(String countKey) {
        boolean isNewKey = Boolean.FALSE.equals(redisTemplate.hasKey(countKey));
        redisTemplate.opsForValue().increment(countKey);
        if (isNewKey) {
            expireAtNextDay5AM(countKey);
        }
    }

    public void expireAtNextDay5AM(String redisKey) {
        // 현재 시각
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        // 다음날 5시 (오전)
        ZonedDateTime next5am = now.plusDays(1).withHour(5).withMinute(0).withSecond(0).withNano(0);

        // Date 로 변환 후 expireAt
        Date expireDate = Date.from(next5am.toInstant());
        redisTemplate.expireAt(redisKey, expireDate);
    }

    private long getCurrentCount(String countKey) {
        String countStr = redisTemplate.opsForValue().get(countKey);
        return (countStr != null) ? Long.parseLong(countStr) : 0L;
    }

    // DB 저장 실패 시 Redis 보상 롤백
    public void rollbackAttendance(Long conferenceId, Long sessionId, Long userId) {
        AttendKeys keys = generateKeys(conferenceId, sessionId);
        redisTemplate.opsForSet().remove(keys.attendanceKey(), userId.toString());
        Long current = redisTemplate.opsForValue().decrement(keys.countKey());
        if (current != null && current < 0) {
            redisTemplate.opsForValue().set(keys.countKey(), "0");
        }
        log.info("[Redis 롤백 완료] userId={}, conferenceId={}, sessionId={}", userId, conferenceId, sessionId);
    }

    // Conference 및 Session 의 Redis 키를 생성
    private AttendKeys generateKeys(Long conferenceId, Long sessionId) {
        if (sessionId == null) {
            return AttendKeys.builder()
                    .attendanceKey("conference:" + conferenceId)
                    .countKey("conference_count:" + conferenceId)
                    .isSession(false)
                    .build();
        }

        return AttendKeys.builder()
                .attendanceKey("conference:" + conferenceId + ":session:" + sessionId)
                .countKey("conference:" + conferenceId + ":session_count:" + sessionId)
                .isSession(true)
                .build();
    }

    // DB 저장 실패 시 Redis List에 이벤트 정보를 저장한다.
    // 형식: "userId:conferenceId:sessionId" (sessionId는 null 가능)
    public void pushFailedEvent(Long userId, Long conferenceId, Long sessionId) {
        String value = userId + ":" + conferenceId + ":" + sessionId;
        redisTemplate.opsForList().leftPush(FAILED_ATTEND_KEY, value);
        log.info("[실패 이벤트 저장] Redis List 적재. userId={}, conferenceId={}, sessionId={}", userId, conferenceId, sessionId);
    }

    // 재처리를 위해 실패 이벤트 목록을 조회한다.
    public List<String> getFailedEvents(int size) {
        List<String> result = redisTemplate.opsForList().range(FAILED_ATTEND_KEY, 0, size - 1);
        return result != null ? result : List.of();
    }

    // 재처리 성공 후 해당 이벤트를 큐에서 제거한다.
    public void removeFailedEvent(String value) {
        redisTemplate.opsForList().remove(FAILED_ATTEND_KEY, 1, value);
    }

    // 모든 참석 키 삭제 - swagger 테스트 시 사용
    public void deleteAllKeys() {
        redisTemplate.delete(redisTemplate.keys("*"));
    }

}
