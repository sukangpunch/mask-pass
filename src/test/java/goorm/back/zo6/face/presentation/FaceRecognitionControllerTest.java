package goorm.back.zo6.face.presentation;

import goorm.back.zo6.auth.application.OAuth2LoginSuccessHandlerFactory;
import goorm.back.zo6.auth.util.JwtUtil;
import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.config.RestDocsConfiguration;
import goorm.back.zo6.face.application.FaceRecognitionService;
import goorm.back.zo6.face.domain.Face;
import goorm.back.zo6.face.dto.response.FaceAuthResultResponse;
import goorm.back.zo6.face.dto.response.FaceResponse;
import goorm.back.zo6.user.application.OAuth2UserServiceFactory;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.multipart.MultipartFile;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.*;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
@ExtendWith(RestDocumentationExtension.class)
@Import(RestDocsConfiguration.class)
class FaceRecognitionControllerTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private RestDocumentationResultHandler restDocs;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FaceRecognitionService rekognitionService;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private JpaMetamodelMappingContext jpaMetamodelMappingContext;

    @MockitoBean
    private OAuth2LoginSuccessHandlerFactory oAuth2LoginSuccessHandlerFactory;

    @MockitoBean
    private OAuth2UserServiceFactory oAuth2UserServiceFactory;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        this.mockMvc = webAppContextSetup(context)
                .apply(springSecurity())
                .apply(documentationConfiguration(restDocumentation))
                .alwaysDo(restDocs)
                .build();

        when(jwtUtil.validateToken(anyString())).thenReturn(true);
        when(jwtUtil.getUserId(anyString())).thenReturn(1L);
        when(jwtUtil.getUsername(anyString())).thenReturn("test@example.com");
        when(jwtUtil.getRole(anyString())).thenReturn("USER");
    }

    @Test
    @DisplayName("얼굴 이미지 업로드 - 성공")
    void uploadUserFace_Success() throws Exception {
        // given
        MockMultipartFile faceImage = new MockMultipartFile("faceImage", "face.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3, 4});
        Face face = Face.builder().id(1L).userId(1L).rekognitionFaceId("rekognition-12345").build();
        FaceResponse mockResponse = FaceResponse.from(face);
        String testToken = "testToken";

        when(rekognitionService.uploadUserFace(eq(1L), any(MultipartFile.class))).thenReturn(mockResponse);

        // when & then
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/face/upload")
                                .file(faceImage)
                                .cookie(new Cookie("Authorization", testToken))
                                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rekognitionId").value("rekognition-12345"))
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.userId").value(1L))
                .andDo(print());
        verify(rekognitionService, times(1)).uploadUserFace(eq(1L), any(MultipartFile.class));
    }

    @Test
    @DisplayName("얼굴 이미지 업로드 - 쿠키가 없을 때 실패")
    void uploadUserFace_WhenCookieNone_Fails() throws Exception {
        // given
        MockMultipartFile faceImage = new MockMultipartFile("faceImage", "face.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3, 4});
        Face face = Face.builder().id(1L).userId(1L).rekognitionFaceId("rekognition-12345").build();
        FaceResponse mockResponse = FaceResponse.from(face);

        when(rekognitionService.uploadUserFace(eq(1L), any(MultipartFile.class))).thenReturn(mockResponse);

        // when & then
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/face/upload")
                                .file(faceImage)
                                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증에 실패하였습니다. 다시 로그인 해 주세요.")) // 응답 메시지 검증
                .andDo(print());
        verifyNoInteractions(rekognitionService);
    }

    @Test
    @DisplayName("얼굴 이미지 삭제 - 성공")
    void deleteFaceImage_Success() throws Exception {
        // given
        String testToken = "testToken";

        // when & then
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/face/delete")
                                .cookie(new Cookie("Authorization", testToken))
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("얼굴 이미지 삭제 완료"))
                .andDo(print());

        verify(rekognitionService, times(1)).deleteUserFace(anyLong());
    }

    @Test
    @DisplayName("얼굴 이미지 삭제 - 쿠키가 없을 때 실패")
    void deleteFaceImage_WhenCookieNone_Fails() throws Exception {
        // given
        // when & then
        mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/face/delete")
                                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("인증에 실패하였습니다. 다시 로그인 해 주세요.")) // 응답 메시지 검증
                .andDo(print());

        verifyNoInteractions(rekognitionService);
    }


    @Test
    @DisplayName("얼굴 인증 - 성공")
    void authenticationByUserFace_Success() throws Exception {
        // given
        Long conferenceId = 1L;
        Long sessionId = 1L;
        Long userId = 1L;
        MockMultipartFile faceImage = new MockMultipartFile("faceImage", "face.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3, 4});
        FaceAuthResultResponse response = new FaceAuthResultResponse(userId, 99.5f);

        when(rekognitionService.authenticationByUserFace(anyLong(), anyLong(), any(MultipartFile.class))).thenReturn(response);

        // when & then
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/face/authentication")
                                .file(faceImage)
                                .param("conferenceId", conferenceId.toString())
                                .param("sessionId", sessionId.toString())
                                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.similarity").value(99.5))
                .andDo(print());

        verify(rekognitionService, times(1)).authenticationByUserFace(conferenceId, sessionId, faceImage);
    }

    @Test
    @DisplayName("얼굴 인증 - 얼굴 인증 실패 테스트")
    void authenticationByUserFace_WhenFaceAuth_Fails() throws Exception {
        // given
        Long conferenceId = 1L;
        Long sessionId = 1L;
        MockMultipartFile faceImage = new MockMultipartFile("faceImage", "face.jpg", MediaType.IMAGE_JPEG_VALUE, new byte[]{1, 2, 3, 4});

        doThrow(new CustomException(ErrorCode.REKOGNITION_NO_MATCH_FOUND))
                .when(rekognitionService).authenticationByUserFace(anyLong(), anyLong(), any(MultipartFile.class));

        // when & then
        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/v1/face/authentication")
                                .file(faceImage)
                                .param("conferenceId", conferenceId.toString())
                                .param("sessionId", sessionId.toString())
                                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Rekognition 얼굴 매칭에 실패하였습니다."))
                .andDo(print());

        verify(rekognitionService, times(1)).authenticationByUserFace(conferenceId, sessionId, faceImage);
    }

    @Test
    @DisplayName("Rekognition Collection 생성 - 성공")
    void createCollection_Success() throws Exception {
        // given
        String testToken = "testToken";

        // when & then
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/face/collection")
                                .contentType(MediaType.APPLICATION_JSON)
                                .cookie(new Cookie("Authorization", testToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("Rekognition Collection 생성 완료!")) // 응답 메시지 검증
                .andDo(print());

        verify(rekognitionService, times(1)).createCollection();
    }
}
