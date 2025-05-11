package goorm.back.zo6.user.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import goorm.back.zo6.user.dto.request.SignUpRequest;
import goorm.back.zo6.user.dto.response.SignUpResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserSignUpServiceImpl implements UserSignUpService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public SignUpResponse signUp(SignUpRequest request) {
        userRepository.findByEmail(request.email())
                .ifPresent(user -> {
                    throw new CustomException(ErrorCode.USER_ALREADY_EXISTS);
                });

        User user = userRepository.save(User.singUpUser(request.email(), request.name(), passwordEncoder.encode(request.password()), request.phone(), Role.of("USER")));

        return SignUpResponse.from(user);
    }

    @Override
    @Transactional
    public SignUpResponse adminSignUp(SignUpRequest request) {
        userRepository.findByEmail(request.email())
                .ifPresent(user -> {
                    throw new CustomException(ErrorCode.USER_ALREADY_EXISTS);
                });

        User user = userRepository.save(User.singUpUser(request.email(), request.name(), passwordEncoder.encode(request.password()), request.phone(), Role.of("ADMIN")));

        return SignUpResponse.from(user);
    }
}
