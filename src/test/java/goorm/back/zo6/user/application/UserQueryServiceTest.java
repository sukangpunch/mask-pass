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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
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

        given(userRepository.findById(userId)).willReturn(Optional.of(testUser));

        // when
        UserResponse response = userQueryService.findById(userId);

        // then
        assertAll(
                () -> assertThat(response).isNotNull(),
                () -> assertThat(response.id()).isEqualTo(userId),
                () -> assertThat(response.email()).isEqualTo("test@gmail.com"),
                () -> assertThat(response.name()).isEqualTo("홍길순"),
                () -> assertThat(response.phone()).isEqualTo("01011112222"),
                () -> assertThat(response.role()).isEqualTo(Role.USER)
        );

        then(userRepository).should(times(1)).findById(userId);
    }

    @Test
    @DisplayName("유저 토큰으로 조회 - 정상적으로 UserResponse 를 반환 성공")
    void findByToken_Success() {
        // given
        String email = "test@gmail.com";
        testUser = createTestUserByEmail(email);

        given(userRepository.findByEmail(email)).willReturn(Optional.of(testUser));

        // when
        UserResponse response = userQueryService.findByEmail(email);

        // then
        assertAll(
                () -> assertThat(response).isNotNull(),
                () -> assertThat(response.email()).isEqualTo("test@gmail.com"),
                () -> assertThat(response.name()).isEqualTo("홍길순"),
                () -> assertThat(response.phone()).isEqualTo("01011112222"),
                () -> assertThat(response.role()).isEqualTo(Role.USER)
        );

        then(userRepository).should(times(1)).findByEmail(email);
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
