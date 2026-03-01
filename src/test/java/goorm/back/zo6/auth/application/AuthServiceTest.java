package goorm.back.zo6.auth.application;

import goorm.back.zo6.auth.util.JwtUtil;
import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.user.domain.Password;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import goorm.back.zo6.auth.dto.request.LoginRequest;
import goorm.back.zo6.auth.dto.response.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private PasswordEncoder passwordEncoder;

    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .name("홍길순")
                .email("test@gmail.com")
                .phone("01011112222")
                .password(Password.from(passwordEncoder.encode("1234")))
                .role(Role.of("USER"))
                .build();
        ReflectionTestUtils.setField(testUser, "id", 1L);

    }

    @Test
    @DisplayName("로그인 -  accessToken 발급 성공")
    void login_Success() {
        // given
        LoginRequest loginRequest = new LoginRequest("test@gmail.com", "1234");

        // db 에서 유저 조회
        when(userRepository.findByEmail(loginRequest.email())).thenReturn(Optional.of(testUser));
        // db 비밀번호 매칭 확인
        when(passwordEncoder.matches(loginRequest.password(), testUser.getPassword().getValue())).thenReturn(true);
        // jwt 토큰 생성
        when(jwtUtil.createAccessToken(testUser.getId(), testUser.getEmail(), testUser.getRole())).thenReturn("mockToken");

        // when
        LoginResponse response = authService.login(loginRequest);

        // then
        assertNotNull(response);
        assertEquals("mockToken", response.accessToken());

        verify(userRepository, times(1)).findByEmail(loginRequest.email());
        verify(passwordEncoder, times(1)).matches(loginRequest.password(), testUser.getPassword().getValue());
        verify(jwtUtil, times(1)).createAccessToken(testUser.getId(), testUser.getEmail(), testUser.getRole());
    }

    @Test
    @DisplayName("로그인 - 존재하지 않는 계정 로그인 실패")
    void login_UserNotFoundFails() {
        // given
        LoginRequest loginRequest = new LoginRequest("nonexistent@gmail.com", "1234");

        // DB에서 유저 조회 실패 (이메일이 존재하지 않음)
        when(userRepository.findByEmail(loginRequest.email()))
                .thenReturn(Optional.empty());

        // when & then
        CustomException exception = assertThrows(CustomException.class, () -> authService.login(loginRequest));

        assertEquals(ErrorCode.USER_NOT_MATCH_LOGIN_INFO, exception.getErrorCode());

        verify(userRepository, times(1)).findByEmail(loginRequest.email());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtUtil, never()).createAccessToken(anyLong(), anyString(), any(Role.class));
    }

    @Test
    @DisplayName("로그인 - 비밀번호가 일치하지 않을 경우 예외 발생 실패")
    void login_InvalidPasswordFails() {
        // given
        LoginRequest loginRequest = new LoginRequest("test@gmail.com", "wrongPassword");

        // 유저는 존재
        when(userRepository.findByEmail(loginRequest.email()))
                .thenReturn(Optional.of(testUser));

        // 비밀번호가 일치하지 않음
        when(passwordEncoder.matches(loginRequest.password(), testUser.getPassword().getValue()))
                .thenReturn(false);

        // when & then
        CustomException exception = assertThrows(CustomException.class, () -> authService.login(loginRequest));

        assertEquals(ErrorCode.USER_NOT_MATCH_LOGIN_INFO, exception.getErrorCode());

        verify(userRepository, times(1)).findByEmail(loginRequest.email());
        verify(passwordEncoder, times(1)).matches(loginRequest.password(), testUser.getPassword().getValue());
        verify(jwtUtil, never()).createAccessToken(anyLong(), anyString(), any(Role.class));
    }
}
