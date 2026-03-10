package goorm.back.zo6.eventstore.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.eventstore.api.EventEntry;
import goorm.back.zo6.eventstore.api.EventStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Repository
public class JpqlEventStore implements EventStore {
    private final ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    @Transactional
    @Modifying
    public void save(Object event) {
        EventEntry entry = new EventEntry(event.getClass().getName(), "application/json", toJson(event));
        entityManager.createNativeQuery(
                        "INSERT INTO evententry (type, content_type, payload, timestamp) VALUES (?, ?, ?, ?)")
                .setParameter(1, entry.getType())
                .setParameter(2, entry.getContentType())
                .setParameter(3, entry.getPayload())
                .setParameter(4, entry.getTimestamp())
                .executeUpdate();
    }

    private String toJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.PAYLOAD_CONVERT_ERROR);
        }
    }

    @Override
    public List<EventEntry> get(long offset, long limit) {
        return entityManager.createNativeQuery(
                        "SELECT * FROM evententry ORDER BY id ASC LIMIT ? OFFSET ?", EventEntry.class)
                .setParameter(1, limit)
                .setParameter(2, offset)
                .getResultList();
    }

}

