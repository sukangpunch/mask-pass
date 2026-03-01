//package goorm.back.zo6.notice.infrastructure;
//
//
//import goorm.back.zo6.conference.domain.Conference;
//import goorm.back.zo6.conference.domain.Session;
//import goorm.back.zo6.conference.infrastructure.ConferenceJpaRepository;
//import goorm.back.zo6.conference.infrastructure.SessionJpaRepository;
//import goorm.back.zo6.fixture.ConferenceFixture;
//import goorm.back.zo6.fixture.SessionFixture;
//import goorm.back.zo6.reservation.domain.Reservation;
//import goorm.back.zo6.reservation.domain.ReservationSession;
//import goorm.back.zo6.reservation.domain.ReservationStatus;
//import goorm.back.zo6.reservation.infrastructure.ReservationJpaRepository;
//import goorm.back.zo6.user.domain.Role;
//import goorm.back.zo6.user.domain.User;
//import goorm.back.zo6.user.infrastructure.UserJpaRepository;
//import jakarta.transaction.Transactional;
//import org.junit.jupiter.api.Assertions;
//import org.junit.jupiter.api.DisplayName;
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
//import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
//import org.springframework.context.annotation.Import;
//import org.springframework.test.context.ActiveProfiles;
//
//import java.util.HashSet;
//import java.util.List;
//import java.util.Set;
//
//@DataJpaTest
//@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
//@ActiveProfiles("test")
//@Transactional
//@Import(ReservationAttendeePhoneDao.class)
//public class ReservationAttendeePhoneDaoTest {
//
//    @Autowired
//    ReservationAttendeePhoneDao reservationAttendeePhoneDao;
//    @Autowired
//    ReservationJpaRepository reservationRepository;
//    @Autowired
//    ConferenceJpaRepository conferenceRepository;
//    @Autowired
//    SessionJpaRepository sessionRepository;
//    @Autowired
//    UserJpaRepository userJpaRepository;
//
//
//
//    @Test
//    @DisplayName("컨퍼런스 id로 예약자 전원의 전화번호를 조회합니다.")
//    public void getAllAttendeeTest(){
//        Conference conference = ConferenceFixture.컨퍼런스();
//        conferenceRepository.save(conference);
//        Reservation reservation1 = Reservation.builder().conference(conference).name("참가자1").phone("010-0000-0001").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation2 = Reservation.builder().conference(conference).name("참가자2").phone("010-0000-0002").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation3 = Reservation.builder().conference(conference).name("참가자3").phone("010-0000-0003").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation4 = Reservation.builder().conference(conference).name("참가자4").phone("010-0000-0004").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation5 = Reservation.builder().conference(conference).name("참가자5").phone("010-0000-0005").status(ReservationStatus.CONFIRMED).build();
//        reservationRepository.saveAll(List.of(reservation1,reservation2,reservation3,reservation4,reservation5));
//        List<String> phones = reservationAttendeePhoneDao.getPhoneConferenceAttendee(conference.getId());
//        Assertions.assertAll(
//                ()->Assertions.assertEquals(phones.size(),5),
//                ()->Assertions.assertEquals(phones.get(0),"010-0000-0001"),
//                ()->Assertions.assertEquals(phones.get(1),"010-0000-0002"),
//                ()->Assertions.assertEquals(phones.get(2),"010-0000-0003"),
//                ()->Assertions.assertEquals(phones.get(3),"010-0000-0004"),
//                ()->Assertions.assertEquals(phones.get(4),"010-0000-0005")
//        );
//
//    }
//
//    @Test
//    @DisplayName("세션 참여자의 id를 조회합니다.")
//    public void getSessionAttendee(){
//        Conference conference = ConferenceFixture.컨퍼런스();
//        conferenceRepository.save(conference);
//        Session session = SessionFixture.세션(conference);
//        sessionRepository.save(session);
//        conference.addSession(session);
//        Reservation reservation1 = Reservation.builder().conference(conference).name("참가자1").phone("010-0000-0001").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation2 = Reservation.builder().conference(conference).name("참가자2").phone("010-0000-0002").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation3 = Reservation.builder().conference(conference).name("참가자3").phone("010-0000-0003").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation4 = Reservation.builder().conference(conference).name("참가자4").phone("010-0000-0004").status(ReservationStatus.CONFIRMED).build();
//        Reservation reservation5 = Reservation.builder().conference(conference).name("참가자5").phone("010-0000-0005").status(ReservationStatus.CONFIRMED).build();
//        reservationRepository.saveAll(List.of(reservation1,reservation2,reservation3,reservation4,reservation5));
//        reservation1.addSession(session);
//        reservation2.addSession(session);
//        reservation3.addSession(session);
//
//        List<String> phones = reservationAttendeePhoneDao.getPhoneSessionAttendee(conference.getId(),session.getId());
//        Assertions.assertAll(
//                ()->Assertions.assertEquals(phones.size(),3),
//                ()->Assertions.assertEquals(phones.get(0),"010-0000-0001"),
//                ()->Assertions.assertEquals(phones.get(1),"010-0000-0002"),
//                ()->Assertions.assertEquals(phones.get(2),"010-0000-0003")
//        );
//    }
//
//    @Test
//    @DisplayName("회원의 id들로 회원의 전화번호를 조회합니다.")
//    public void getPhoneByIdTest(){
//        User user1 = User.builder()
//                .name("유저1")
//                .email("test1@gmail.com")
//                .phone("010111122221")
//                .role(Role.of("USER"))
//                .build();
//
//        User user2 = User.builder()
//                .name("유저2")
//                .email("test2@gmail.com")
//                .phone("010111122222")
//                .role(Role.of("USER"))
//                .build();
//        User user3 = User.builder()
//                .name("유저3")
//                .email("test3@gmail.com")
//                .phone("010111122223")
//                .role(Role.of("USER"))
//                .build();
//
//        userJpaRepository.saveAll(List.of(user1,user2,user3));
//        Set<String> ids = new HashSet<>();
//        ids.add(user1.getId().toString());
//        ids.add(user2.getId().toString());
//        ids.add(user3.getId().toString());
//        List<String> phones = reservationAttendeePhoneDao.getPhoneByUserId(ids);
//        Assertions.assertAll(
//                ()->Assertions.assertEquals(phones.get(0),"010111122221"),
//                ()->Assertions.assertEquals(phones.get(1),"010111122222"),
//                ()->Assertions.assertEquals(phones.get(2),"010111122223")
//        );
//    }
//}
