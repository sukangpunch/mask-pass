package goorm.back.zo6.user.application;

import goorm.back.zo6.common.exception.CustomException;
import goorm.back.zo6.common.exception.ErrorCode;
import goorm.back.zo6.user.domain.Password;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
import goorm.back.zo6.user.dto.request.SignUpRequest;
import goorm.back.zo6.user.dto.response.SignUpResponse;
import goorm.back.zo6.user.dto.response.UserResponse;
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
class UserServiceTest {

    @InjectMocks
    private UserSignUpServiceImpl userSignUpService;
    @InjectMocks
    private UserCommandServiceImpl userCommandService;
    @InjectMocks
    private UserQueryServiceImpl userQueryService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    private User testUser;

    private User createTestUserByUserId(Long userId) {
        User user = User.builder()
                .name("홍길순")
                .email("test@gmail.com")
                .phone("01011112222")
                .password(Password.from(passwordEncoder.encode("1234")))
                .role(Role.of("USER"))
                .build();
        ReflectionTestUtils.setField(user,"id",userId);

        return user;
    }

    private User createTestUserByEmail(String email) {
        return User.builder()
                .name("홍길순")
                .email(email)
                .phone("01011112222")
                .password(Password.from(passwordEncoder.encode("1234")))
                .role(Role.of("USER"))
                .build();
    }

    @Test
    @DisplayName("유저 ID로 조회- 정상적으로 UserResponse 를 반환 성공")
    void findById_Success() {
        // given
        Long userId = 1L;
        testUser = createTestUserByUserId(userId);

        // findById()가 정상적으로 유저를 반환
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        // when
        UserResponse response = userQueryService.findById(userId);

        // then
        assertNotNull(response);
        assertEquals("test@gmail.com", response.email());
        assertEquals("홍길순", response.name());
        assertEquals("01011112222", response.phone());
        assertEquals("test@gmail.com", response.email());
        assertEquals(Role.USER, response.role());
        
        // Verify
        verify(userRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("유저 토큰으로 조회 - 정상적으로 UserResponse 를 반환 성공")
    void findByToken_Success() {
        // given
        String email = "test@gmail.com";
        testUser = createTestUserByEmail(email);

        // findById()가 정상적으로 유저를 반환
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser));

        // when
        UserResponse response = userQueryService.findByEmail(email);

        // then
        assertNotNull(response);
        assertEquals("test@gmail.com", response.email());
        assertEquals("홍길순", response.name());
        assertEquals("01011112222", response.phone());
        assertEquals("test@gmail.com", response.email());
        assertEquals(Role.USER, response.role());

        // Verify
        verify(userRepository, times(1)).findByEmail(email);
    }

    @Test
    @DisplayName("회원가입 - SignUpResponse 를 반환 성공")
    void signUp_Success() {
        // given
        SignUpRequest request = new SignUpRequest("홍길동","newuser@gmail.com", "1234", "01033334444");

        // 기존 이메일이 존재하지 않음
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        //  비밀번호 암호화
        when(passwordEncoder.encode(request.password())).thenReturn("encodedPassword");
        //  새로운 유저 저장 후 반환
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

        // Verify
        verify(userRepository, times(1)).findByEmail(request.email());
        verify(passwordEncoder, times(1)).encode(request.password());
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    @DisplayName("회원가입 - 이미 존재하는 계정으로 회원가입 예외 발생 실패")
    void signUp_UserAlreadyExistsFails() {
        // given
        SignUpRequest request = new SignUpRequest("홍길동","exist@gmail.com", "1234", "01033334444");

        // 이미 존재하는 유저
        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(User.builder().build()));

        // when
        CustomException exception = assertThrows(CustomException.class, () -> userSignUpService.signUp(request));

        // then
        assertEquals(ErrorCode.USER_ALREADY_EXISTS, exception.getErrorCode());

        // Verify
        verify(userRepository, times(1)).findByEmail(request.email());
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("유저 논리 탈퇴(비활성화) - 유저 논리 삭제(비활성화) 정상적으로 성공")
    void deactivateByToken_Success() {
        //given
        Long userId = 1L;
        testUser = createTestUserByUserId(userId);
        String email = testUser.getEmail();

        // 유저 조회
        when(userRepository.findByEmail(email)).thenReturn(Optional.of(testUser));
        // deleteById 가 정상적으로 실행됨
        doNothing().when(userRepository).deleteById(userId);

        //when
        userCommandService.deactivateByToken(testUser.getEmail());

        //then
        verify(userRepository, times(1)).deleteById(userId);
        verify(userRepository, times(1)).findByEmail(email);
    }

    @Test
    @DisplayName("유저 논리 탈퇴(비활성화) - 존재하지 않는 유저 회원 논리 탈퇴(비활성화) 시 예외 발생 실패")
    void deactivateByToken_NotFoundFails() {
        //given
        String nonExistentEmail = "nonexistent@gmail.com";

        // 조회 결과가 empty
        when(userRepository.findByEmail(nonExistentEmail)).thenReturn(Optional.empty());

        // When
        CustomException exception = assertThrows(CustomException.class, () -> userCommandService.deactivateByToken(nonExistentEmail));

        // then
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
        verify(userRepository,times(1)).findByEmail(nonExistentEmail);
    }

}