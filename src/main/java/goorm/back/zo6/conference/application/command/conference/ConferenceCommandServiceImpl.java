package goorm.back.zo6.conference.application.command.conference;

import goorm.back.zo6.conference.application.dto.ConferenceCreateRequest;
import goorm.back.zo6.conference.application.dto.ConferenceResponse;
import goorm.back.zo6.conference.application.shared.ConferenceFactory;
import goorm.back.zo6.conference.application.shared.ConferenceMapper;
import goorm.back.zo6.conference.application.shared.ConferenceValidator;
import goorm.back.zo6.conference.domain.Conference;
import goorm.back.zo6.conference.domain.ConferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ConferenceCommandServiceImpl implements ConferenceCommandService {

    private final ConferenceRepository conferenceRepository;
    private final ConferenceMapper conferenceMapper;
    private final ConferenceValidator conferenceValidator;
    private final ConferenceFactory conferenceFactory;

    @Override
    public boolean getConferenceStatus(Long conferenceId) {

        Conference conference = conferenceValidator.findConferenceOrThrow(conferenceId);
        return conference.getIsActive();
    }

    @Override
    @Transactional
    public void updateConferenceStatus(Long conferenceId, boolean newStatus) {

        Conference conference = conferenceValidator.findConferenceOrThrow(conferenceId);
        conference.updateActive(newStatus);
    }

    @Override
    @Transactional
    public ConferenceResponse createConference(ConferenceCreateRequest request) {

        Conference conference = conferenceFactory.createConference(request);

        Conference savedConference = conferenceRepository.save(conference);
        return conferenceMapper.toConferenceResponse(savedConference);
    }
}
