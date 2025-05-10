package goorm.back.zo6.user.application;

import goorm.back.zo6.user.domain.Password;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.User;
import goorm.back.zo6.user.domain.UserRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
public class UserQueryServiceTest {

    @InjectMocks
    private UserQueryServiceImpl userQueryService;

    @Mock
    private UserRepository userRepository;
    @Mock
    private PasswordEncoder passwordEncoder;

    private User testUser;

    @Test
    @DisplayName("유저 ID로 조회- 정상적으로 UserResponse 를 반환 성공")
    void findById_Success() {
        // given
        Long userId = 1L;
        testUser = createTestUserByUserId(userId);

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

        verify(userRepository, times(1)).findById(userId);
    }

    @Test
    @DisplayName("유저 토큰으로 조회 - 정상적으로 UserResponse 를 반환 성공")
    void findByToken_Success() {
        // given
        String email = "test@gmail.com";
        testUser = createTestUserByEmail(email);

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

        verify(userRepository, times(1)).findByEmail(email);
    }

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
}
