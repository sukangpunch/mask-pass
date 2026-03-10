package goorm.back.zo6.attend.application;

import goorm.back.zo6.attend.domain.Attend;
import goorm.back.zo6.attend.domain.AttendRepository;
import goorm.back.zo6.attend.dto.*;
import goorm.back.zo6.attend.infrastructure.AttendRedisService;
import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Log4j2
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Service
public class AttendService {

    private final AttendRepository attendRepository;
    private final UserRepository userRepository;
    private final AttendRedisService attendRedisService;
    private final AttendDtoConverter attendDtoConverter;

    // 참석 정보 저장
    @Transactional
    public void registerAttend(Long userId, Long conferenceId, Long sessionId) {
        log.info("참석 정보 rdb 저장, userId : {}, conferenceId : {} ,sessionId : {}", userId, conferenceId, sessionId);
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        AttendData attendDataDto = attendRepository.findAttendInfo(user.getPhone(), conferenceId, sessionId);
        Attend attend = convertToAttend(user.getId(), attendDataDto);

        attendRepository.save(attend);
    }

    // 컨퍼런스, 세션 의 유저 및 참석형태, 메타데이터 조회
    public AttendanceSummaryResponse getAttendanceSummary(Long conferenceId, Long sessionId) {
        AttendanceSummaryQuery query = attendRepository.findAttendanceSummary(conferenceId, sessionId);
        long attendCount = attendRedisService.attendCount(conferenceId, sessionId);

        return AttendanceSummaryResponse.of(query.getTitle(), query.getCapacity(), attendCount, query.getUserAttendances());
    }

    // 해당 유저의 컨퍼런스/세션 참석 데이터를 조회
    public ConferenceInfoResponse findAllByToken(Long userId, Long conferenceId) {
        ConferenceInfoResponse conferenceInfoDto = attendRepository.findAttendInfoByUserAndConference(userId, conferenceId);
        return attendDtoConverter.convertConferenceInfoResponse(conferenceInfoDto);
    }

    // 참석 객체로 변환
    private Attend convertToAttend(Long id, AttendData attendDataDto) {
        return Attend.of(id, attendDataDto.getReservationId(), attendDataDto.getReservationSessionId(), attendDataDto.getConferenceId(), attendDataDto.getSessionId());
    }
}

