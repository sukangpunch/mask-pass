package goorm.back.zo6.face.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.face.dto.response.FaceMatchingResponse;
import java.nio.ByteBuffer;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.CreateCollectionRequest;
import software.amazon.awssdk.services.rekognition.model.CreateCollectionResponse;
import software.amazon.awssdk.services.rekognition.model.DeleteFacesRequest;
import software.amazon.awssdk.services.rekognition.model.DeleteFacesResponse;
import software.amazon.awssdk.services.rekognition.model.Face;
import software.amazon.awssdk.services.rekognition.model.FaceMatch;
import software.amazon.awssdk.services.rekognition.model.FaceRecord;
import software.amazon.awssdk.services.rekognition.model.IndexFacesRequest;
import software.amazon.awssdk.services.rekognition.model.IndexFacesResponse;
import software.amazon.awssdk.services.rekognition.model.SearchFacesByImageRequest;
import software.amazon.awssdk.services.rekognition.model.SearchFacesByImageResponse;

@ExtendWith(MockitoExtension.class)
class RekognitionApiClientTest {

    @InjectMocks
    private RekognitionApiClient rekognitionApiClient;
    @Mock
    private RekognitionClient rekognitionClient;

    private String collectionId;

    @BeforeEach
    void setUp() {
        collectionId = "test-collection-name";
        // Value 값 직접 주입
        ReflectionTestUtils.setField(rekognitionApiClient, "collectionId", "test-collection-name");
    }

    @Test
    @DisplayName("Rekognition Collection 에 얼굴 등록 - 성공")
    void addFaceToCollection_Success() {
        // given
        Long userId = 1L;
        String rekognitionFaceId = "rekognition-1234";
        Face face = Face.builder().faceId(rekognitionFaceId).build();
        FaceRecord faceRecord = FaceRecord.builder().face(face).build();
        IndexFacesResponse response = IndexFacesResponse.builder()
                .faceRecords(faceRecord)
                .build();
        byte[] imageBytes = new byte[]{1, 2, 3};

        // Rekognition API 응답
        when(rekognitionClient.indexFaces(any(IndexFacesRequest.class))).thenReturn(response);

        // when
        String result = rekognitionApiClient.addFaceToCollection(userId, imageBytes);

        // then
        assertEquals(rekognitionFaceId, result);
        verify(rekognitionClient, times(1)).indexFaces(any(IndexFacesRequest.class));
    }

    // Rekognition 이 얼굴을 감지하지 못한 경우 예외 발생 테스트
    @Test
    @DisplayName("Rekognition Collection 에 얼굴 등록 - 얼굴 등록 실패 시 예외 발생")
    void addFaceToCollection_WhenFaceUploadFails() {
        // given
        Long userId = 1L;
        byte[] imageBytes = new byte[]{1, 2, 3};

        // 얼굴을 감지하지 못한 응답 설정
        IndexFacesResponse response = IndexFacesResponse.builder()
                .faceRecords(Collections.emptyList())
                .build();

        when(rekognitionClient.indexFaces(any(IndexFacesRequest.class))).thenReturn(response);

        // when & then
        // 예외 발생 검증
        CustomException exception = assertThrows(CustomException.class, () -> rekognitionApiClient.addFaceToCollection(userId, imageBytes));

        // API 호출 확인
        assertEquals(ErrorCode.FACE_UPLOAD_FAIL, exception.getErrorCode());
        verify(rekognitionClient, times(1)).indexFaces(any(IndexFacesRequest.class));
    }

    @Test
    @DisplayName("Rekognition Collection 에 얼굴 등록 - Rekognition Api 예외 발생")
    void addFaceToCollection_WhenApiFails() {
        // given
        Long userId = 1L;
        byte[] imageBytes = new byte[]{1, 2, 3};

        doThrow(new CustomException(ErrorCode.REKOGNITION_API_FAILURE))
                .when(rekognitionClient).indexFaces(any(IndexFacesRequest.class));

        // when & then
        // 예외 발생 검증
        CustomException exception = assertThrows(CustomException.class, () -> rekognitionApiClient.addFaceToCollection(userId, imageBytes));

        // API 호출 확인
        assertEquals(ErrorCode.REKOGNITION_API_FAILURE, exception.getErrorCode());
        verify(rekognitionClient, times(1)).indexFaces(any(IndexFacesRequest.class));
    }

    @Test
    @DisplayName("얼굴 인증(매칭) - 성공")
    void authorizeUserFace_Success() {
        // given
        String userId = "1";
        ByteBuffer imageBytes = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});
        float similarity = 85f;

        FaceMatch mockFaceMatch = FaceMatch.builder()
                .face(Face.builder().externalImageId(userId).build())
                .similarity(similarity)
                .build();

        SearchFacesByImageResponse mockResponse = SearchFacesByImageResponse.builder()
                .faceMatches(Collections.singletonList(mockFaceMatch))
                .build();

        // 얼굴 인증(매칭) 성공!
        when(rekognitionClient.searchFacesByImage(Mockito.any(SearchFacesByImageRequest.class)))
                .thenReturn(mockResponse);

        // when
        FaceMatchingResponse response = rekognitionApiClient.authorizeUserFace(imageBytes);
        System.out.println("result : " + response.userId());
        System.out.println("expect: " + userId);
        // then
        assertNotNull(response);
        assertEquals(similarity, response.similarity());
        assertEquals(Long.parseLong(userId), response.userId());
    }

    @Test
    @DisplayName("얼굴 인증(매칭) - 매칭되는 얼굴이 존재하지 않아 실패")
    void authorizeUserFace_WhenMatchingFails() {
        // given
        ByteBuffer imageBytes = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});

        SearchFacesByImageResponse mockResponse = SearchFacesByImageResponse.builder()
                .faceMatches(Collections.emptyList())
                .build();

        // 얼굴 인증(매칭) 실패!
        when(rekognitionClient.searchFacesByImage(any(SearchFacesByImageRequest.class)))
                .thenReturn(mockResponse);

        // when & then
        CustomException exception = assertThrows(CustomException.class, () -> rekognitionApiClient.authorizeUserFace(imageBytes));

        // then
        assertEquals(ErrorCode.REKOGNITION_NO_MATCH_FOUND, exception.getErrorCode());
        verify(rekognitionClient, times(1)).searchFacesByImage(any(SearchFacesByImageRequest.class));
    }

    @Test
    @DisplayName("얼굴 인증(매칭) - 얼굴 매칭 중 api 서버 에러 실패")
    void authorizeUserFace_WhenApiFails() {
        // given
        ByteBuffer imageBytes = ByteBuffer.wrap(new byte[]{1, 2, 3, 4});

        // 얼굴 인증(매칭) api 처리 실패!
        doThrow(new CustomException(ErrorCode.REKOGNITION_API_FAILURE))
                .when(rekognitionClient).searchFacesByImage(any(SearchFacesByImageRequest.class));

        // when & then
        CustomException exception = assertThrows(CustomException.class,
                                                 () -> rekognitionApiClient.authorizeUserFace(imageBytes));

        // then
        verify(rekognitionClient, times(1)).searchFacesByImage(any(SearchFacesByImageRequest.class));
        assertEquals(ErrorCode.REKOGNITION_API_FAILURE, exception.getErrorCode());
    }

    @Test
    @DisplayName("얼굴 삭제 - Rekognition Collection 에서 얼굴 이미지 삭제")
    void deleteFaceFromCollection_Success() {
        // given
        String rekognitionFaceId = "rekognition-1234";
        DeleteFacesRequest request = DeleteFacesRequest.builder()
                .collectionId(collectionId)
                .faceIds(rekognitionFaceId).build();

        DeleteFacesResponse response = DeleteFacesResponse.builder().build();

        when(rekognitionClient.deleteFaces(any(DeleteFacesRequest.class)))
                .thenReturn(response);

        // when
        rekognitionApiClient.deleteFaceFromCollection(rekognitionFaceId);

        // then
        verify(rekognitionClient, times(1)).deleteFaces(request);
    }

    @Test
    @DisplayName("얼굴 삭제 - Rekognition API 서버 에러 실패")
    void deleteFaceFromCollection_WhenApiFails() {
        // given
        String rekognitionFaceId = "rekognition-1234";
        DeleteFacesRequest request = DeleteFacesRequest.builder()
                .collectionId(collectionId)
                .faceIds(rekognitionFaceId).build();

        DeleteFacesResponse response = DeleteFacesResponse.builder().build();

        when(rekognitionClient.deleteFaces(any(DeleteFacesRequest.class)))
                .thenReturn(response);

        // when
        rekognitionApiClient.deleteFaceFromCollection(rekognitionFaceId);

        // then
        verify(rekognitionClient, times(1)).deleteFaces(request);
    }

    @Test
    @DisplayName("Rekognition Collection 생성 - 성공")
    void createCollection_Success() {
        // given
        String expectedArn = "arn:aws:rekognition:us-east-1:123456789012:collection/test-collection";
        CreateCollectionRequest request = CreateCollectionRequest.builder()
                .collectionId(collectionId)
                .build();

        CreateCollectionResponse response = CreateCollectionResponse.builder()
                .collectionArn(expectedArn)
                .build();

        when(rekognitionClient.createCollection(request)).thenReturn(response);

        // when
        String resultArn = rekognitionApiClient.createCollection();

        // then
        assertNotNull(resultArn);
        assertEquals(expectedArn, resultArn);
        verify(rekognitionClient, times(1)).createCollection(request);
    }

    @Test
    @DisplayName("Rekognition Collection 생성 - rekognition api 에러 실패")
    void createCollection_WhenApiFails() {
        // given
        CreateCollectionRequest request = CreateCollectionRequest.builder()
                .collectionId(collectionId)
                .build();

        doThrow(new RuntimeException())
                .when(rekognitionClient).createCollection(request);

        // when
        Exception exception = assertThrows(CustomException.class,
                                           () -> rekognitionApiClient.createCollection());

        // then
        assertNotNull(exception);
        verify(rekognitionClient, times(1)).createCollection(request);
    }
}
