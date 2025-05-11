package goorm.back.zo6.auth.application;

import goorm.back.zo6.auth.util.JwtUtil;
import goorm.back.zo6.user.domain.Role;
import goorm.back.zo6.user.domain.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public abstract class AbstractOAuth2LoginSuccessHandler implements AuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final UserRepository userRepository;

    protected abstract String getEmail(OAuth2User oAuth2User);

    protected abstract Long getUserId(OAuth2User oAuth2User);

    protected abstract Role getRole(OAuth2User oAuth2User);

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();

        Long userId = getUserId(oAuth2User);
        String email = getEmail(oAuth2User);
        Role role = getRole(oAuth2User);

        String accessToken = jwtUtil.createAccessToken(userId, email, role);
        boolean hasPhone = isHasPhone(email);

        Cookie cookie = createAccessTokenCookie(accessToken);
        response.addCookie(cookie);

        String redirectUrl = "https://www.maskpass.site/authorization/callback?&hasPhone=" + hasPhone;
        response.sendRedirect(redirectUrl);
    }

    private boolean isHasPhone(String email) {
        return userRepository.findByEmail(email)
                .map(user -> user.getPhone() != null)
                .orElse(false);
    }

    private Cookie createAccessTokenCookie(String accessToken) {
        Cookie cookie = new Cookie("Authorization", accessToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge((int) TimeUnit.HOURS.toSeconds(2));
        return cookie;
    }
}
