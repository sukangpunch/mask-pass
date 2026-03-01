package goorm.back.zo6.face.application;

import goorm.back.zo6.common.event.Events;
import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.face.domain.Face;
import goorm.back.zo6.face.domain.FaceRepository;
import goorm.back.zo6.face.dto.response.CollectionResponse;
import goorm.back.zo6.face.dto.response.FaceAuthResultResponse;
import goorm.back.zo6.face.dto.response.FaceMatchingResponse;
import goorm.back.zo6.face.dto.response.FaceResponse;
import goorm.back.zo6.face.infrastructure.RekognitionApiClient;
import goorm.back.zo6.attend.domain.AttendEvent;
import goorm.back.zo6.reservation.domain.ReservationRepository;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FaceRecognitionServiceTest {

    @InjectMocks
    private FaceRecognitionService faceRecognitionService;
    @Mock
    private RekognitionApiClient rekognitionApiClient;
    @Mock
    private FaceRepository faceRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("얼굴 이미지 업로드 - 성공")
    void uploadUserFace_Success() throws IOException {
        // given
        Long userId = 1L;
        String rekognitionFaceId = "rekog-12345";
        Face face = Face.of(rekognitionFaceId, userId);
        User user = User.singUpUser("test@test", "홍길순", "12345", "01011112222", Role.USER);
        MultipartFile testFaceImage = mock(MultipartFile.class);
        byte[] imageBytes = new byte[]{1, 2, 3};

        when(userRepository.findById(any(Long.class))).thenReturn(Optional.ofNullable(user));
        when(testFaceImage.getBytes()).thenReturn(imageBytes);
        // collection 저장 성공
        when(rekognitionApiClient.addFaceToCollection(userId, imageBytes)).thenReturn(rekognitionFaceId);
        // Face 객체 저장
        when(faceRepository.save(any(Face.class))).thenReturn(face);

        // when
        FaceResponse response = faceRecognitionService.uploadUserFace(userId, testFaceImage);

        // then
        assertNotNull(response);
        assertEquals(rekognitionFaceId, response.rekognitionId());

        verify(rekognitionApiClient, times(1)).addFaceToCollection(userId, imageBytes);
        verify(faceRepository, times(1)).save(any(Face.class));
        verify(userRepository, times(1)).findById(any(Long.class));
    }

    @Test
    @DisplayName("얼굴 이미지 업로드 - Rekognition Collection 에 얼굴 이미지 등록이 실패할 경우 예외 발생")
    void uploadUserFace_WhenRekognitionFails() throws IOException {
        // given
        Long userId = 1L;
        User user = User.singUpUser("test@test", "홍길순", "12345", "01011112222", Role.USER);
        MultipartFile testFaceImage = mock(MultipartFile.class);
        byte[] imageBytes = new byte[]{1, 2, 3};

        when(userRepository.findById(any(Long.class))).thenReturn(Optional.ofNullable(user));
        when(testFaceImage.getBytes()).thenReturn(imageBytes);

        // Rekognition 등록 실패
        doThrow(new CustomException(ErrorCode.FACE_UPLOAD_FAIL))
                .when(rekognitionApiClient).addFaceToCollection(eq(userId), eq(imageBytes));

        // when
        CustomException exception = assertThrows(CustomException.class, () -> faceRecognitionService.uploadUserFace(userId, testFaceImage));

        // then
        assertEquals(ErrorCode.FACE_UPLOAD_FAIL, exception.getErrorCode());
        // Rekognition 호출이 된다.
        verify(rekognitionApiClient, times(1)).addFaceToCollection(userId, imageBytes);
        verify(faceRepository, times(1)).findFaceByUserId(userId);
    }

    @Test
    @DisplayName("얼굴 이미지 삭제 - 성공!")
    void deleteFaceImage_Success() {
        // given
        Long userId = 1L;
        String rekognitionFaceId = "rekog-12345";
        User user = User.singUpUser("test@test", "홍길순", "12345", "01011112222", Role.USER);
        Face face = Face.of(rekognitionFaceId, userId);

        when(userRepository.findById(any(Long.class))).thenReturn(Optional.ofNullable(user));
        when(faceRepository.findFaceByUserId(userId)).thenReturn(Optional.of(face));
        doNothing().when(faceRepository).deleteByUserId(userId);
        doNothing().when(rekognitionApiClient).deleteFaceFromCollection(rekognitionFaceId);

        // when
        faceRecognitionService.deleteUserFace(userId);

        // then
        verify(faceRepository, times(1)).findFaceByUserId(userId);
        verify(faceRepository, times(1)).deleteByUserId(userId);
        verify(rekognitionApiClient, times(1)).deleteFaceFromCollection(rekognitionFaceId);
        verify(userRepository, times(1)).findById(any(Long.class));
    }

    @Test
    @DisplayName("얼굴 이미지 삭제 실패 - FaceRepository 조회 실패 시 예외 발생")
    void deleteFaceImage_WhenFaceNotFound() {
        // given
        Long userId = 1L;
        User user = User.singUpUser("test@test", "홍길순", "12345", "01011112222", Role.USER);

        when(userRepository.findById(any(Long.class))).thenReturn(Optional.ofNullable(user));

        // FaceRepository 에서 유저 얼굴 정보가 없을 경우 예외 발생하도록 설정
        doThrow(new CustomException(ErrorCode.FACE_NOT_FOUND))
                .when(faceRepository).findFaceByUserId(userId);

        // when
        CustomException exception = assertThrows(CustomException.class, () -> faceRecognitionService.deleteUserFace(userId));

        // then
        assertEquals(ErrorCode.FACE_NOT_FOUND, exception.getErrorCode());
        // FaceRepository 는 호출이 된다.
        verify(faceRepository, times(1)).findFaceByUserId(userId);
        verify(userRepository, times(1)).findById(any(Long.class));
        // S3와 Rekognition API가 호출되지 않아야 함
        verifyNoInteractions(rekognitionApiClient);
    }

    @Test
    @DisplayName("얼굴 이미지 삭제 실패 - Rekognition 얼굴 삭제 실패 시 예외 발생")
    void deleteFaceImage_WhenRekognitionDeletionFails() {
        // given
        Long userId = 1L;
        String rekognitionFaceId = "rekog-12345";
        Face face = Face.of(rekognitionFaceId, userId);
        User user = User.singUpUser("test@test", "홍길순", "12345", "01011112222", Role.USER);

        when(userRepository.findById(any(Long.class))).thenReturn(Optional.ofNullable(user));
        when(faceRepository.findFaceByUserId(userId)).thenReturn(Optional.of(face));

        // Rekognition 얼굴 삭제 실패 설정
        doThrow(new CustomException(ErrorCode.REKOGNITION_DELETE_FAILED))
                .when(rekognitionApiClient).deleteFaceFromCollection(rekognitionFaceId);

        // when
        CustomException exception = assertThrows(CustomException.class, () -> faceRecognitionService.deleteUserFace(userId));

        // then
        assertEquals(ErrorCode.REKOGNITION_DELETE_FAILED, exception.getErrorCode());
        // Rekognition 삭제 호출
        verify(rekognitionApiClient, times(1)).deleteFaceFromCollection(rekognitionFaceId);
        // DB 삭제는 호출되지 않음
        verify(faceRepository, never()).deleteByUserId(userId);
        verify(userRepository, times(1)).findById(any(Long.class));
    }

    @Test
    @DisplayName("얼굴 인식 - 성공")
    void authenticationByUserFace_Success() throws IOException {
        // given
        Long userId = 1L;
        float similarity = 92.5f;
        MultipartFile uploadedFile = mock(MultipartFile.class);
        Long conferenceId = 1L;
        Long sessionId = 1L;
        MockedStatic<Events> mockEvents = mockStatic(Events.class);

        // 정상적인 파일 변환 설정
        mockEvents.when(() -> Events.raise(any(AttendEvent.class))).thenAnswer(invocation -> null);
        when(uploadedFile.getBytes()).thenReturn(new byte[]{1, 2, 3});
        ByteBuffer imageBytes = ByteBuffer.wrap(new byte[]{1, 2, 3});

        // Rekognition API가 정상적으로 응답을 반환하도록 설정
        FaceMatchingResponse matchingResponse = FaceMatchingResponse.of(userId, similarity);
        when(rekognitionApiClient.authorizeUserFace(imageBytes)).thenReturn(matchingResponse);

        // 예매 한 유저 처리
        when(reservationRepository.existsByUserAndConferenceAndSession(userId, conferenceId, sessionId)).thenReturn(true);
        // when
        FaceAuthResultResponse result = faceRecognitionService.authenticationByUserFace(conferenceId, sessionId, uploadedFile);

        // then
        assertNotNull(result);
        assertEquals(userId, result.userId());
        assertEquals(similarity, result.similarity());
        verify(rekognitionApiClient, times(1)).authorizeUserFace(imageBytes);
        verify(reservationRepository, times(1)).existsByUserAndConferenceAndSession(userId, conferenceId, sessionId);
    }

    @Test
    @DisplayName("얼굴 인식 - 매치되는 얼굴이 없어서 인증 실패")
    void authenticationByUserFace_WhenMatchFails() throws IOException {
        // given
        MultipartFile uploadedFile = mock(MultipartFile.class);
        Long conferenceId = 1L;
        Long sessionId = 1L;

        // 정상적인 파일 변환 설정
        when(uploadedFile.getBytes()).thenReturn(new byte[]{1, 2, 3});
        ByteBuffer imageBytes = ByteBuffer.wrap(new byte[]{1, 2, 3});

        // Rekognition API 얼굴 매칭 실패
        doThrow(new CustomException(ErrorCode.REKOGNITION_NO_MATCH_FOUND))
                .when(rekognitionApiClient).authorizeUserFace(imageBytes);

        // when
        CustomException exception = assertThrows(CustomException.class, () -> faceRecognitionService.authenticationByUserFace(conferenceId, sessionId, uploadedFile));

        // then
        assertEquals(ErrorCode.REKOGNITION_NO_MATCH_FOUND, exception.getErrorCode());
        verify(rekognitionApiClient, times(1)).authorizeUserFace(imageBytes);
    }

    // 파일 변환 중 IOException 발생 시 예외 확인
    @Test
    @DisplayName("얼굴 인식 - 파일 변환 중 IOException 발생 시 예외 발생")
    void authenticationByUserFace_WhenIOException() throws IOException {
        // given
        MultipartFile uploadedFile = mock(MultipartFile.class);
        Long conferenceId = 1L;
        Long sessionId = 1L;

        // 파일 변환 중 IOException 발생하도록 설정
        when(uploadedFile.getBytes()).thenThrow(new IOException());

        // when
        CustomException exception = assertThrows(CustomException.class, () -> faceRecognitionService.authenticationByUserFace(conferenceId, sessionId, uploadedFile));

        // then
        assertEquals(ErrorCode.FILE_CONVERSION_EXCEPTION, exception.getErrorCode());
        verifyNoInteractions(rekognitionApiClient);
    }

    @Test
    @DisplayName("rekognition collection 생성 - 성공")
    void createCollection_Success() {
        // given
        // collection 생성
        String expectedArn = "arn:aws:rekognition:us-east-1:123456789012:collection/test-collection";

        when(rekognitionApiClient.createCollection()).thenReturn(expectedArn);

        // when
        CollectionResponse response = faceRecognitionService.createCollection();

        // then
        assertNotNull(response);
        assertEquals(expectedArn, response.collectionArn());
        verify(rekognitionApiClient, times(1)).createCollection();
    }

    @Test
    @DisplayName("rekognition collection 생성 - collection 생성 실패")
    void createCollection_Fails() {
        // given
        // collection 생성시 에러 발생
        doThrow(new CustomException(ErrorCode.REKOGNITION_CREATE_COLLECTION_FAIL))
                .when(rekognitionApiClient).createCollection();

        // when
        CustomException exception = assertThrows(CustomException.class, () -> faceRecognitionService.createCollection());

        //then
        assertEquals(ErrorCode.REKOGNITION_CREATE_COLLECTION_FAIL, exception.getErrorCode());
        verify(rekognitionApiClient, times(1)).createCollection();
    }
}
