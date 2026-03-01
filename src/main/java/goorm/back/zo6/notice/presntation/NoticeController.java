//package goorm.back.zo6.notice.presntation;
//
//import goorm.back.zo6.common.dto.ResponseDto;
//import goorm.back.zo6.notice.application.NoticeService;
//import goorm.back.zo6.notice.dto.NoticeRequest;
//import goorm.back.zo6.notice.dto.NoticeResponseDto;
//import io.swagger.v3.oas.annotations.Operation;
//import io.swagger.v3.oas.annotations.tags.Tag;
//import jakarta.validation.Valid;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.http.MediaType;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.File;
//import java.io.IOException;
//import java.util.List;

/**
 * 이번 리팩토링에서는 사용하지 않을 예정
 */

//@Tag(name = "Notices", description = "알림 전송 API")
//@RestController
//@RequiredArgsConstructor
//@Slf4j
//@RequestMapping("/api/v1/notices")
//public class NoticeController {
//    private final NoticeService noticeService;
//
//    @PostMapping(value="/{conferenceId}",consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    @Operation(summary = "알림 전송", description = "알림 대상을 ALL, ATTENDEE, NON_ATTENDEE 중 하나로 보내주세요. 세션에 대한 전송은 파라미터로 sessionId를 보내주시면 됩니다.")
//    public ResponseEntity<ResponseDto<String>> sendNotice(@PathVariable Long conferenceId,
//                                                         @RequestParam(value = "sessionId", required = false) Long sessionId,
//                                                         @Valid @RequestPart(value = "noticeRequest") NoticeRequest noticeRequest,
//                                                         @RequestPart(value = "image", required = false) MultipartFile image) throws IOException {
//        File tempImage = null;
//        if (image != null && !image.isEmpty()) {
//            tempImage = File.createTempFile("temp", ".tmp");
//            image.transferTo(tempImage);
//        }
//        noticeService.sendMessage(noticeRequest.message(),conferenceId,sessionId,noticeRequest.noticeTarget(), tempImage);
//        return ResponseEntity.ok(ResponseDto.of("메시지 전송 완료"));
//    }
//
//    @GetMapping("/{conferenceId}")
//    @Operation(summary = "알림 전송 내역 확인", description = "전송된 알림의 내역을 확인하고 싶은 세션 또는 세션,컨퍼런스 아이디르 보내주세요. 세션에 대한 조회는 파라미터로 sessionId를 보내주시면 됩니다.")
//    public ResponseEntity<ResponseDto<List<NoticeResponseDto>>> getNotice(@PathVariable Long conferenceId,
//                                                              @RequestParam(value = "sessionId", required = false) Long sessionId){
//        return ResponseEntity.ok(ResponseDto.of(noticeService.getMessages(conferenceId,sessionId)));
//    }
//
//}
