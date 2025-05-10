package goorm.back.zo6.user.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.reservation.application.command.ReservationCommandService;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import goorm.back.zo6.user.dto.request.SignUpRequest;
import goorm.back.zo6.user.dto.response.SignUpResponse;
import goorm.back.zo6.user.dto.response.UserResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@RequiredArgsConstructor
@Log4j2
@Service
@Transactional(readOnly = true)
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PhoneValidService phoneValidService;
    private final UserValidator userValidator;
    private final ReservationCommandService reservationCommandService;

    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        userRepository.findByEmail(request.email())
                .ifPresent(user -> {
                    throw new CustomException(ErrorCode.USER_ALREADY_EXISTS);
                });

        User user = userRepository.save(User.singUpUser(request.email(), request.name(), passwordEncoder.encode(request.password()), request.phone(), Role.of("USER")));

        return SignUpResponse.from(user);
    }

    @Transactional
    public SignUpResponse adminSignUp(SignUpRequest request) {
        userRepository.findByEmail(request.email())
                .ifPresent(user -> {
                    throw new CustomException(ErrorCode.USER_ALREADY_EXISTS);
                });

        User user = userRepository.save(User.singUpUser(request.email(), request.name(), passwordEncoder.encode(request.password()), request.phone(), Role.of("ADMIN")));

        return SignUpResponse.from(user);
    }

    @Transactional
    public SignUpResponse signUpWithPhone(SignUpRequest request) {

        userValidator.validatePhone(request.phone());

        User user = userRepository.save(User.singUpUser(request.email(), request.name(), passwordEncoder.encode(request.password()), request.phone(), Role.of("USER")));

        reservationCommandService.linkReservationByPhone(request.phone());

        phoneValidService.removeVerifiedPhone(request.phone());

        return SignUpResponse.from(user);
    }

    public UserResponse findById(Long userId) {
        User user = userRepository.findById(userId).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserResponse.from(user);
    }

    public UserResponse findByToken(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        return UserResponse.from(user);
    }

    public boolean isEmailAvailable(String email) {
        return userRepository.findByEmail(email).isEmpty();
    }

    @Transactional
    public void deactivateByToken(String email) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        userRepository.deleteById(user.getId());
    }

    @Transactional
    public void initPhoneNumber(String email, String phone) {
        User user = userRepository.findByEmail(email).orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        user.initPhone(phone);
    }
}
