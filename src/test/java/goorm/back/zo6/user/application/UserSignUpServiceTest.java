package goorm.back.zo6.user.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import goorm.back.zo6.user.dto.request.SignUpRequest;
import goorm.back.zo6.user.dto.response.SignUpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
public class UserSignUpServiceTest {

    @InjectMocks
    private UserSignUpServiceImpl userSignUpService;

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("회원가입 - SignUpResponse 를 반환 성공")
    void signUp_Success() {
        // given
        SignUpRequest request = new SignUpRequest("홍길동","newuser@gmail.com", "1234", "01033334444");

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(request.password())).thenReturn("encodedPassword");

        User newUser = User.singUpUser(request.email(), request.name(), "encodedPassword", request.phone(), Role.of("USER"));
        ReflectionTestUtils.setField(newUser,"id",1L);
        when(userRepository.save(any(User.class))).thenReturn(newUser);

        // when
        SignUpResponse response = userSignUpService.signUp(request);

        // then
        assertNotNull(response);
        assertNotNull(response.id());
        assertEquals("newuser@gmail.com", response.email());
        assertEquals("홍길동", response.name());
        assertEquals("01033334444", response.phone());
        assertEquals(Role.USER, response.role());

        verify(userRepository, times(1)).findByEmail(request.email());
        verify(passwordEncoder, times(1)).encode(request.password());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 - 이미 존재하는 계정으로 회원가입 예외 발생 실패")
    void signUp_UserAlreadyExistsFails() {
        // given
        SignUpRequest request = new SignUpRequest("홍길동","exist@gmail.com", "1234", "01033334444");

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(User.builder().build()));

        // when
        CustomException exception = assertThrows(CustomException.class, () -> userSignUpService.signUp(request));

        // then
        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getErrorCode());

        verify(userRepository, times(1)).findByEmail(request.email());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }
}
