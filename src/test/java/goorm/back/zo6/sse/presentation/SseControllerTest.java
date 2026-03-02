package goorm.back.zo6.sse.presentation;

import goorm.back.zo6.config.RestDocsConfiguration;
import goorm.back.zo6.sse.infrastructure.EmitterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.hibernate.validator.internal.util.Contracts.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ExtendWith(RestDocumentationExtension.class)
@Import(RestDocsConfiguration.class)
class SseControllerTest {

    private static final String USER_ID = "device-uuid-test";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RestDocumentationResultHandler restDocs;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EmitterRepository emitterRepository;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        this.mockMvc = webAppContextSetup(context)
                .apply(springSecurity())
                .apply(documentationConfiguration(restDocumentation))
                .alwaysDo(restDocs)
                .build();
    }

    @Test
    @DisplayName("SSE 구독 요청 - SseEmitter 반환 구독 성공")
    void subscribe_Success() throws Exception {
        // given
        Long conferenceId = 1L;
        Long sessionId = 2L;

        // when & then
        mockMvc.perform(get("/api/v1/sse/subscribe")
                        .param("conferenceId", String.valueOf(conferenceId))
                        .param("sessionId", String.valueOf(sessionId))
                        .param("userId", USER_ID)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM_VALUE));

        String baseKey = "conference:" + conferenceId + ":session:" + sessionId;
        Map<String, SseEmitter> savedEmitters = emitterRepository.findEmittersByBaseKey(baseKey);

        assertNotNull(savedEmitters.get(USER_ID));
    }

    @Test
    @DisplayName("SSE 구독 요청 - conferenceId 없이 요청하면 400 Bad Request")
    void subscribe_NoneConferenceFails() throws Exception {
        // given
        Long sessionId = 2L;

        // when & then
        mockMvc.perform(get("/api/v1/sse/subscribe")
                        .param("sessionId", String.valueOf(sessionId))
                        .param("userId", USER_ID)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SSE 구독 요청 - userId 없이 요청하면 400 Bad Request")
    void subscribe_NoneUserIdFails() throws Exception {
        // given
        Long conferenceId = 1L;
        Long sessionId = 2L;

        // when & then
        mockMvc.perform(get("/api/v1/sse/subscribe")
                        .param("conferenceId", String.valueOf(conferenceId))
                        .param("sessionId", String.valueOf(sessionId))
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("SSE 구독 요청 - 존재하지 않는 키로 Emitter를 조회하면 null을 반환한다")
    void subscribe_NonExistentKey_ReturnsNull() throws Exception {
        // given
        Long conferenceId = 1L;
        Long sessionId = 2L;

        // 정상적으로 요청
        mockMvc.perform(get("/api/v1/sse/subscribe")
                        .param("conferenceId", String.valueOf(conferenceId))
                        .param("sessionId", String.valueOf(sessionId))
                        .param("userId", USER_ID)
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk());

        // 존재하지 않는 키로 조회
        Map<String, SseEmitter> emitters = emitterRepository.findEmittersByBaseKey("conference:-1:session:-2");

        assertNull(emitters.get(USER_ID));
    }
}
