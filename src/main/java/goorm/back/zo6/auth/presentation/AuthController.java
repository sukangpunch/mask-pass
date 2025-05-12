package goorm.back.zo6.auth.presentation;

import goorm.back.zo6.auth.application.AuthService;
import goorm.back.zo6.auth.dto.request.LoginRequest;
import goorm.back.zo6.auth.dto.response.LoginResponse;
import goorm.back.zo6.auth.util.CookieUtil;
import goorm.back.zo6.common.dto.ResponseDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "auth", description = "Authorization API")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    @Value("${cookie.valid-time}")
    private long COOKIE_VALID_TIME;

    @Value("${cookie.name}")
    private String COOKIE_NAME;

    private final AuthService authService;

    @PostMapping("/login")
    @Operation(summary = "로그인", description = "이메일과 비밀번호를 입력받아 인증합니다. <br>" +
            " 브라우저에 쿠키가 심어져 요청 시 자동으로 jwt 값이 추가됩니다.")
    public ResponseEntity<ResponseDto<LoginResponse>> login(@Validated @RequestBody LoginRequest request) {
        LoginResponse loginResponse = authService.login(request);
        ResponseCookie cookie = CookieUtil.createCookie(COOKIE_NAME, loginResponse.accessToken(), COOKIE_VALID_TIME);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ResponseDto.of(loginResponse));
    }

    @DeleteMapping("/logout")
    @Operation(summary = "로그아웃", description = "api 요청 시 쿠키를 강제 만료시켜 로그아웃합니다.")
    public ResponseEntity<ResponseDto> logout() {
        ResponseCookie cookie = CookieUtil.deleteCookie(COOKIE_NAME);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ResponseDto.of("로그아웃 성공!"));
    }

}
