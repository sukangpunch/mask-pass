package goorm.back.zo6.conference.application.shared;

import goorm.back.zo6.conference.application.dto.ConferenceResponse;
import goorm.back.zo6.conference.application.dto.SessionDto;
import goorm.back.zo6.conference.domain.Conference;
import goorm.back.zo6.conference.domain.Session;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class ConferenceMapper {

    public ConferenceResponse toConferenceResponse(Conference conference) {
        return ConferenceResponse.from(conference, conference.getImageKey());
    }

    public ConferenceResponse toConferenceDetailResponse(Conference conference) {
        List<SessionDto> sortedSessions = conference.getSessions().stream()
                .sorted(Comparator.comparing(Session::getStartTime))
                .map(this::toSessionDto)
                .toList();

        return ConferenceResponse.detailFrom(conference, conference.getImageKey(), sortedSessions);
    }

    public ConferenceResponse toConferenceSimpleResponse(Conference conference) {
        return ConferenceResponse.simpleFrom(conference, conference.getImageKey());
    }

    public SessionDto toSessionDto(Session session) {
        return SessionDto.from(session, session.getSpeakerImageKey());
    }
}
