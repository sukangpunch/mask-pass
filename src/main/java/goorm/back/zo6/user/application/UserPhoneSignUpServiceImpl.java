//package goorm.back.zo6.user.application;
//
//import goorm.back.zo6.reservation.application.command.ReservationCommandService;
//import goorm.back.zo6.user.domain.Role;
//import goorm.back.zo6.user.domain.User;
//import goorm.back.zo6.user.domain.UserRepository;
//import goorm.back.zo6.user.dto.request.SignUpRequest;
//import goorm.back.zo6.user.dto.response.SignUpResponse;
//import lombok.RequiredArgsConstructor;
//import org.springframework.security.crypto.password.PasswordEncoder;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;

/**
 * 이번 리팩토링에서는 사용하지 않을 예정
 */

//@Service
//@RequiredArgsConstructor
//public class UserPhoneSignUpServiceImpl implements UserPhoneSignUpService {
//
//    private final UserRepository userRepository;
//    private final UserValidator userValidator;
//    private final PasswordEncoder passwordEncoder;
//    private final ReservationCommandService reservationCommandService;
//    private final PhoneValidService phoneValidService;
//
//    @Override
//    @Transactional
//    public SignUpResponse signUpWithPhone(SignUpRequest request) {
//        userValidator.validatePhone(request.phone());
//        User user = userRepository.save(User.singUpUser(request.email(), request.name(), passwordEncoder.encode(request.password()), request.phone(), Role.of("USER")));
//
//        reservationCommandService.linkReservationByPhone(request.phone());
//
//        phoneValidService.removeVerifiedPhone(request.phone());
//
//        return SignUpResponse.from(user);
//    }
//}
