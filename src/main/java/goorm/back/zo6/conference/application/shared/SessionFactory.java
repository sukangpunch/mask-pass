package goorm.back.zo6.conference.application.shared;

import goorm.back.zo6.conference.application.dto.SessionCreateRequest;
import goorm.back.zo6.conference.application.dto.SessionDto;
import goorm.back.zo6.conference.domain.Conference;
import goorm.back.zo6.conference.domain.Session;
import goorm.back.zo6.reservation.domain.Reservation;
import goorm.back.zo6.reservation.domain.ReservationSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SessionFactory {

    private final ConferenceMapper conferenceMapper;

    public Set<SessionDto> createSessionDtos(List<Reservation> reservations) {
        return reservations.stream()
                .flatMap(reservation -> reservation.getReservationSessions().stream())
                .map(ReservationSession::getSession)
                .map(conferenceMapper::toSessionDto)
                .collect(Collectors.toSet());
    }

    public Session createSession(SessionCreateRequest request, Conference conference) {
        return Session.builder()
                        .name(request.name())
                        .capacity(request.capacity())
                        .location(request.location())
                        .startTime(request.startTime())
                        .endTime(request.endTime())
                        .summary(request.summary())
                        .speakerName(request.speakerName())
                        .speakerOrganization(request.speakerOrganization())
                        .isActive(true)
                        .speakerImageKey(request.speakerImage())
                        .conference(conference)
                        .build();
    }
}
