package goorm.back.zo6.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;


@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // User Error
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 유저입니다."),
    USER_ALREADY_EXISTS(HttpStatus.NOT_FOUND, "이미 존재하는 유저입니다."),
    PHONE_NOT_VERIFIED(HttpStatus.NOT_ACCEPTABLE, "번호 인증 실패"),

    // Login Error
    USER_NOT_MATCH_LOGIN_INFO(HttpStatus.BAD_REQUEST, "로그인 정보에 해당하는 유저가 존재하지 않습니다."),

    // Role Error
    ROLE_INVALID(HttpStatus.BAD_REQUEST, "유효하지 않은 Role 입니다."),
    FORBIDDEN_ACCESS(HttpStatus.FORBIDDEN, "권한 없음"),

    // Invalidation Error
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    INVALID_JSON_FORMAT(HttpStatus.BAD_REQUEST, "요청한 JSON 형식이 올바르지 않습니다."),
    INVALID_SESSION_CAPACITY(HttpStatus.BAD_REQUEST, "세션 정원 값이 잘못되었습니다."),
    INVALID_SESSION_TIME(HttpStatus.BAD_REQUEST, "세션 시간이 잘못되었습니다. 과거 시간일 수 없습니다."),
    INVALID_SESSION_NAME(HttpStatus.BAD_REQUEST, "세션 이름을 입력해야 합니다."),
    INVALID_SESSION_LOCATION(HttpStatus.BAD_REQUEST, "세션 장소를 입력해야 합니다."),
    INVALID_RESERVATION_STATUS(HttpStatus.BAD_REQUEST, "알 수 없거나 처리할 수 없는 예약 상태입니다."),

    // JWT Error
    WRONG_TYPE_TOKEN(HttpStatus.UNAUTHORIZED,"토큰의 서명이 유효하지 않습니다."),
    UNSUPPORTED_TOKEN(HttpStatus.UNAUTHORIZED,"잘못된 형식의 토큰입니다."),
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED,"만료된 토큰입니다."),
    UNKNOWN_TOKEN_ERROR(HttpStatus.BAD_REQUEST,"토큰의 값이 존재하지 않습니다."),
    MISSING_TOKEN(HttpStatus.BAD_REQUEST, "토큰이 존재하지 않습니다."),

    // Rekognition Error
    REKOGNITION_DELETE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Rekognition Collection 에서 이미지 삭제를 실패하였습니다."),
    REKOGNITION_NO_MATCH_FOUND(HttpStatus.BAD_REQUEST, "Rekognition 얼굴 매칭에 실패하였습니다."),
    REKOGNITION_API_FAILURE(HttpStatus.INTERNAL_SERVER_ERROR,"Rekognition Api 호출 시 문제가 발생하였습니다."),
    REKOGNITION_CREATE_COLLECTION_FAIL(HttpStatus.INTERNAL_SERVER_ERROR, "Rekognition Collection 생성 실패"),
    USER_NOT_RESERVED(HttpStatus.BAD_REQUEST,"예매 내역에 존재하지 않는 유저가 인증을 시도하였습니다."),

    // Face Error
    FACE_UPLOAD_FAIL(HttpStatus.BAD_REQUEST,"유저 얼굴 이미지 저장 실패하였습니다."),
    FACE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 유저의 얼굴 이미지 저장 정보를 조회할 수 없습니다."),
    UNAUTHORIZED_RESERVATION(HttpStatus.BAD_REQUEST, "얼굴 매칭은 성공하였으나, 예매 내역을 찾을 수 없습니다."),

    // Conference Error
    CONFERENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 컨퍼런스입니다."),
    CONFERENCE_HAS_NO_SESSION(HttpStatus.BAD_REQUEST, "해당 컨퍼런스에 세션이 없습니다."),

    // Session Error
    SESSION_NOT_FOUNT(HttpStatus.NOT_FOUND, "존재하지 않는 세션입니다."),
    SESSION_NOT_BELONG_TO_CONFERENCE(HttpStatus.BAD_REQUEST, "해당 세션은 컨퍼런스에 속해 있지 않습니다."),

    // File Exception
    FILE_CONVERSION_EXCEPTION(HttpStatus.BAD_REQUEST, "파일 변환 중에 에러가 발생했습니다."),

    // Session Error
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 세션입니다."),

    // Reservation Error
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "존재하지 않는 예약입니다."),
    RESERVATION_NOT_PHONE(HttpStatus.NOT_FOUND, "입력한 전화번호는 임시 예약이 없습니다"),
    RESERVATION_ALREADY_CONFIRMED(HttpStatus.BAD_REQUEST, "이미 확정된 예약입니다."),
    ALREADY_LINKED_USER(HttpStatus.BAD_REQUEST, "이미 사용자와 연결된 예약입니다."),
  
    // Event Error
    PAYLOAD_CONVERT_ERROR(HttpStatus.BAD_REQUEST, "이벤트 내용 변환 중 에러 발생"),

    // Attendance Error
    MISSING_REQUIRED_PARAMETER(HttpStatus.BAD_REQUEST, "conferenceId 또는 sessionId 가 필요합니다."),
    NO_ATTENDANCE_DATA(HttpStatus.NO_CONTENT, "해당 컨퍼런스/세션 에 참석 유저 데이터가 존재하지 않습니다."),

    // Encryption Error
    ENCRYPT_CIPHER_EXCEPTION(HttpStatus.BAD_REQUEST, "암호화 과정에서 에러가 발생했습니다."),
    DECRYPT_CIPHER_EXCEPTION(HttpStatus.BAD_REQUEST,"복호화 과정에서 에러가 발생했습니다."),

    // Notice Error
    TARGET_ERROR(HttpStatus.BAD_REQUEST,"메시지 전송 타켓이 잘못되었습니다."),

    // Sse Error
    SSE_CONNECTION_FAILED(HttpStatus.BAD_REQUEST, "SSE 전송 중 오류가 발생했습니다."),

    // Phone Valid
    EXPIRED_PHONE(HttpStatus.BAD_REQUEST,"인증번호가 만료되었습니다.")
    ;

    private final HttpStatus status;
    private final String message;

}
