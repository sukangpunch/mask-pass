//package goorm.back.zo6.notice.integration;
//
//
//import goorm.back.zo6.conference.domain.Conference;
//import goorm.back.zo6.conference.infrastructure.ConferenceJpaRepository;
//import goorm.back.zo6.fixture.ConferenceFixture;
//import goorm.back.zo6.notice.application.NoticeService;
//import goorm.back.zo6.notice.domain.Notice;
//import goorm.back.zo6.notice.domain.NoticeTarget;
//import goorm.back.zo6.notice.infrastructure.NoticeRepository;
//import goorm.back.zo6.reservation.domain.Reservation;
//import goorm.back.zo6.reservation.domain.ReservationStatus;
//import goorm.back.zo6.reservation.infrastructure.ReservationJpaRepository;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.http.MediaType;
//import org.springframework.mock.web.MockMultipartFile;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.web.servlet.MockMvc;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.web.multipart.MultipartFile;
//
//import java.io.File;
//import java.io.FileInputStream;
//import java.io.IOException;
//import java.util.List;
//
//import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
//import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
//import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
//
//@SpringBootTest
//@AutoConfigureMockMvc
//@Transactional
//@ActiveProfiles("test")
//public class NoticeIntegrationTest {
//
//    @Autowired
//    NoticeService noticeService;
//    @Autowired
//    ReservationJpaRepository reservationRepository;
//    @Autowired
//    ConferenceJpaRepository conferenceRepository;
//    @Autowired
//    NoticeRepository noticeRepository;
//
//    @Autowired
//    MockMvc mockMvc;
//
//    /*@Test
//    @DisplayName("컨퍼런스의 참여자에게 문자 메세지를 전송합니다.")
//    public void sendMessageTest() throws IOException {
//        Conference conference = ConferenceFixture.컨퍼런스();
//        conferenceRepository.save(conference);
//        Reservation reservation1 = Reservation.builder().conference(conference).name("참가자1").phone("").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation2 = Reservation.builder().conference(conference).name("참가자2").phone("").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation3 = Reservation.builder().conference(conference).name("참가자2").phone("").status(ReservationStatus.CONFIRMED).build();
//        reservationRepository.saveAll(List.of(reservation1, reservation2, reservation3));
//        noticeService.sendMessage("테스트 메시지\n6조 화이팅",conference.getId(),null,"ALL",null);
//    }
//
//     */
//
//  /*  @Test
//    @DisplayName("컨퍼런스 미참여자에게 문자 메세지를 전송합니다.")
//    public void sendMessageToNonAttendeeTest() throws IOException {
//        Conference conference = ConferenceFixture.컨퍼런스();
//        conferenceRepository.save(conference);
//        Reservation reservation1 = Reservation.builder().conference(conference).name("참가자1").phone("01034510018").status(ReservationStatus.CONFIRMED).build();
//        //Reservation reservation2 = Reservation.builder().conference(conference).name("참가자2").phone("010-0000-0002").build();
//        reservationRepository.saveAll(List.of(reservation1));
//
//        // 로컬 이미지 파일을 MultipartFile로 변환
//        File file = new File("C:\\Users\\hen71\\Desktop\\디자이너 이혜성\\KakaoTalk_20241006_203006000.jpg"); // 로컬 이미지 경로 설정
//        FileInputStream input = new FileInputStream(file);
//        MultipartFile imageFile = new MockMultipartFile("image", file.getName(), "image/jpeg", input);
//        noticeService.sendMessage("테스트 메시지\n6조 화이팅",conference.getId(),null,"NON_ATTENDEE",imageFile);
//    }*/
//
//    @Test
//    @DisplayName("문자발송 내역을 조회합니다. - 컨퍼런스만 조회")
//    public void getMessageOnlyConference() throws Exception {
//        Notice notice1 = Notice.builder().message("메시지1").noticeTarget(NoticeTarget.ALL).conferenceId(1L).sessionId(null).build();
//        Notice notice2 = Notice.builder().message("메시지2").noticeTarget(NoticeTarget.ALL).conferenceId(1L).sessionId(null).build();
//        Notice notice3 = Notice.builder().message("메시지3").noticeTarget(NoticeTarget.ALL).conferenceId(1L).sessionId(3L).build();
//        Notice notice4 = Notice.builder().message("메시지4").noticeTarget(NoticeTarget.ALL).conferenceId(2L).sessionId(null).build();
//        Notice notice5 = Notice.builder().message("메시지5").noticeTarget(NoticeTarget.ALL).conferenceId(2L).sessionId(4L).build();
//        noticeRepository.saveAll(List.of(notice1,notice2,notice3,notice4,notice5));
//
//        mockMvc.perform(get("/api/v1/notices/1").contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.data.length()").value(2))
//                .andExpect(jsonPath("$.data[0].message").value("메시지1"))
//                .andExpect(jsonPath("$.data[1].message").value("메시지2"))
//                .andDo(print());
//    }
//
//
//    @Test
//    @DisplayName("문자발송 내역을 조회합니다. - 컨퍼런스와 세션 조회")
//    public void getMessage() throws Exception {
//        Notice notice1 = Notice.builder().message("메시지1").noticeTarget(NoticeTarget.ALL).conferenceId(1L).sessionId(null).build();
//        Notice notice2 = Notice.builder().message("메시지2").noticeTarget(NoticeTarget.ALL).conferenceId(1L).sessionId(null).build();
//        Notice notice3 = Notice.builder().message("메시지3").noticeTarget(NoticeTarget.ALL).conferenceId(1L).sessionId(3L).build();
//        Notice notice4 = Notice.builder().message("메시지4").noticeTarget(NoticeTarget.ALL).conferenceId(2L).sessionId(null).build();
//        Notice notice5 = Notice.builder().message("메시지5").noticeTarget(NoticeTarget.ALL).conferenceId(2L).sessionId(4L).build();
//        noticeRepository.saveAll(List.of(notice1,notice2,notice3,notice4,notice5));
//
//        mockMvc.perform(get("/api/v1/notices/1").param("sessionId","3").contentType(MediaType.APPLICATION_JSON))
//                .andExpect(status().isOk())
//                .andExpect(jsonPath("$.data.length()").value(1))
//                .andExpect(jsonPath("$.data[0].message").value("메시지3"))
//                .andDo(print());
//    }
//}
