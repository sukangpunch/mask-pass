//package goorm.back.zo6.notice.domain;
//
//
//import goorm.back.zo6.common.base.BaseEntity;
//import jakarta.persistence.*;
//import lombok.AccessLevel;
//import lombok.Builder;
//import lombok.Getter;
//import lombok.NoArgsConstructor;

/**
 * 이번 리팩토링에서는 사용하지 않을 예정
 */

//@NoArgsConstructor(access = AccessLevel.PROTECTED)
//@Entity
//@Getter
//@Table(name = "notices")
//public class Notice extends BaseEntity {
//
//    @Id
//    @GeneratedValue(strategy = GenerationType.IDENTITY)
//    @Column(name = "notice_id")
//    private Long id;
//
//    @Column(name = "message")
//    private String message;
//
//    @Column(name = "notice_target")
//    @Enumerated(EnumType.STRING)
//    private NoticeTarget noticeTarget;
//
//    @Column(name = "conference_id")
//    private Long conferenceId;
//
//    @Column(name = "session_id")
//    private Long sessionId;
//
//    @Builder
//    public Notice (String message, NoticeTarget noticeTarget , Long conferenceId, Long sessionId){
//        this.message = message;
//        this.noticeTarget = noticeTarget;
//        this.conferenceId = conferenceId;
//        this.sessionId = sessionId;
//    }
//
//}
