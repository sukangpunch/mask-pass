package goorm.back.zo6.attend.application;

import goorm.back.zo6.attend.dto.ConferenceInfoResponse;
import goorm.back.zo6.attend.dto.SessionInfo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class AttendDtoConverter {

    public ConferenceInfoResponse convertConferenceInfoResponse(ConferenceInfoResponse original) {
        List<SessionInfo> sessionsWithUrl = null;

        if (original.getSessions() != null) {
            sessionsWithUrl = original.getSessions().stream()
                    .map(this::convertSessionInfo)
                    .collect(Collectors.toList());
        }

        return new ConferenceInfoResponse(
                original.getId(),
                original.getName(),
                original.getDescription(),
                original.getLocation(),
                original.getStartTime(),
                original.getEndTime(),
                original.getCapacity(),
                original.getHasSessions(),
                original.getImageUrl(),
                original.getIsActive(),
                original.isAttend(),
                sessionsWithUrl
        );
    }

    private SessionInfo convertSessionInfo(SessionInfo original) {
        return new SessionInfo(
                original.getId(),
                original.getName(),
                original.getCapacity(),
                original.getLocation(),
                original.getStartTime(),
                original.getEndTime(),
                original.getSummary(),
                original.getSpeakerName(),
                original.getSpeakerOrganization(),
                original.getSpeakerImageKey(),
                original.isActive(),
                original.isAttend()
        );
    }
}
