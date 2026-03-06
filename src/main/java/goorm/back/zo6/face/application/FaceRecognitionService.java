package goorm.back.zo6.face.application;

import goorm.back.zo6.attend.domain.AttendEvent;
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
import goorm.back.zo6.reservation.domain.ReservationRepository;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Log4j2
@Transactional(readOnly = true)
public class FaceRecognitionService {
    private final RekognitionApiClient rekognitionApiClient;
    private final FaceRepository faceRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;

    // 얼굴 데이터 collection 저장
    @Transactional
    public FaceResponse uploadUserFace(Long userId, MultipartFile faceImage){
        User user = userRepository.findById(userId).orElseThrow(()-> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 이미 얼굴 등록이 완료한 유저는 기존에 등록되었던 얼굴 이미지 삭제
        deleteFaceForUploadIfExists(userId);

        // 이미지 데이터를 Rekognition Collection 에 등록
        byte[] imageBytes = toBytes(faceImage);
        String rekognitionFaceId = rekognitionApiClient.addFaceToCollection(userId, imageBytes);

        // DB에 얼굴 정보 저장
        Face face = faceRepository.save(Face.of(rekognitionFaceId, userId));
        log.info("얼굴 등록 완료! userId: {}, Face ID: {}", userId, rekognitionFaceId);
        user.faceRegistration();

        return FaceResponse.from(face);
    }

    // 얼굴 데이터 collection 에서 삭제
    @Transactional
    public void deleteUserFace(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(()-> new CustomException(ErrorCode.USER_NOT_FOUND));
        Face face = faceRepository.findFaceByUserId(userId).orElseThrow(()-> new CustomException(ErrorCode.FACE_NOT_FOUND));

        String rekognitionId = face.getRekognitionFaceId();
        // Rekognition Collection 에 저장된 이미지 삭제
        rekognitionApiClient.deleteFaceFromCollection(rekognitionId);
        // DB 에서 삭제mask_pass_db
        faceRepository.deleteByUserId(userId);
        log.info("얼굴 데이터 삭제 완료! userId : {}", userId);
        user.deleteFace();
    }

    // 얼굴 비교 및 인증
    @Transactional
    public FaceAuthResultResponse authenticationByUserFace(Long conferenceId, Long sessionId, MultipartFile uploadedFile) {

        ByteBuffer imageBytes = ByteBuffer.wrap(toBytes(uploadedFile));
        FaceMatchingResponse response = rekognitionApiClient.authorizeUserFace(imageBytes);

        if (!validateReservation(response.userId(), conferenceId, sessionId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_RESERVATION);
        }

        Events.raise(new AttendEvent(response.userId(), conferenceId, sessionId));
        return FaceAuthResultResponse.of(response.userId(), response.similarity());
    }

    // rekognition collection 생성, 초기 1회 실행
    public CollectionResponse createCollection(){
        String collectionArl = rekognitionApiClient.createCollection();
        return CollectionResponse.of(collectionArl);
    }

    public void deleteCollection(){
        rekognitionApiClient.deleteCollection();
    }

    private byte[] toBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new CustomException(ErrorCode.FILE_CONVERSION_EXCEPTION);
        }
    }

    private boolean validateReservation(Long userId, Long conferenceId, Long sessionId) {
        boolean isReserved = (sessionId == null)
                ? reservationRepository.existsByUserIdAndConferenceId(userId, conferenceId)
                : reservationRepository.existsByUserAndConferenceAndSession(userId, conferenceId, sessionId);

        log.info("isReserved : {}", isReserved);
        return isReserved;
    }

    private void deleteFaceForUploadIfExists(Long userId) {
        faceRepository.findFaceByUserId(userId).ifPresent(face -> {
            rekognitionApiClient.deleteFaceFromCollection(face.getRekognitionFaceId());
            faceRepository.deleteByUserId(userId);
            log.info("업로드를 위한 기존 얼굴 정보 삭제 완료 - userId: {}, faceId: {}", userId, face.getRekognitionFaceId());
        });
    }
}
