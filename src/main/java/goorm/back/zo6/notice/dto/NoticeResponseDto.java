//package goorm.back.zo6.notice.dto;
//
//import goorm.back.zo6.notice.domain.Notice;
//import goorm.back.zo6.notice.domain.NoticeTarget;
//import lombok.Builder;
//import lombok.Getter;
//import lombok.NoArgsConstructor;
//import org.springframework.format.annotation.DateTimeFormat;
//
//import java.time.LocalDateTime;

/**
 * 이번 리팩토링에서는 사용하지 않을 예정
 */

//@Getter
//@NoArgsConstructor
//public class NoticeResponseDto {
//    private String message;
//    private NoticeTarget noticeTarget;
//    @DateTimeFormat(pattern = "yyyy.MM.dd HH:mm:ss")
//    private LocalDateTime createdAt;
//
//    @Builder
//    private NoticeResponseDto(String message, NoticeTarget noticeTarget, LocalDateTime createdAt){
//        this.message= message;
//        this.noticeTarget = noticeTarget;
//        this.createdAt = createdAt;
//    }
//    public static NoticeResponseDto from(Notice notice){
//        return NoticeResponseDto.builder()
//                .message(notice.getMessage())
//                .noticeTarget(notice.getNoticeTarget())
//                .createdAt(notice.getCreatedAt())
//                .build();
//    }
//}
