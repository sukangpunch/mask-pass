package goorm.back.zo6.conference.application.shared;

import goorm.back.zo6.conference.application.dto.ConferenceCreateRequest;
import goorm.back.zo6.conference.domain.Conference;
import org.springframework.stereotype.Component;

@Component
public class ConferenceFactory {

    public Conference createConference(ConferenceCreateRequest request) {
        return Conference.builder()
                .name(request.name())
                .description(request.description())
                .capacity(request.capacity())
                .location(request.location())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .imageKey(request.imageUrl())
                .isActive(true)
                .hasSessions(request.hasSessions())
                .build();
    }
}
