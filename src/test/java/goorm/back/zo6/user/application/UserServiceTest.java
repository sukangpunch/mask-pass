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