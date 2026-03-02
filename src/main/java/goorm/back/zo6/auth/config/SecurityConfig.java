package goorm.back.zo6.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import goorm.back.zo6.auth.application.OAuth2LoginSuccessHandlerFactory;
import goorm.back.zo6.auth.exception.CustomAccessDeniedHandler;
import goorm.back.zo6.auth.exception.CustomAuthenticationEntryPoint;
import goorm.back.zo6.auth.filter.JwtAuthFilter;
import goorm.back.zo6.auth.util.JwtUtil;
import goorm.back.zo6.user.application.OAuth2UserServiceFactory;
import goorm.back.zo6.user.domain.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtUtil jwtUtil;
    private final ObjectMapper objectMapper;
    private final OAuth2UserServiceFactory oAuth2UserServiceFactory;
    private final OAuth2LoginSuccessHandlerFactory successHandlerFactory;


    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // CORS 설정 (프론트엔드 도메인 허용 필요 시 커스터마이징)
        http.cors(cors -> cors.configurationSource(configurationSource()));

        // CSRF, Form 로그인, HTTP Basic 인증 비활성화 (JWT 기반 인증 사용)
        http.csrf(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);

        // URL 경로별 접근 제어 설정
        http.authorizeHttpRequests(auth -> auth
                // Swagger 및 시스템 모니터링 경로 - 문서 확인 및 상태 체크용
                .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-resources/**", "/webjars/**", "/actuator/**").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                // 사용자 인증 관련 공개 API - 회원가입, 로그인, 이메일 인증 등
                .requestMatchers(
                        "/api/v1/users/signup", "/api/v1/auth/login",
                        "/api/v1/users/signup-link", "/api/v1/users/check-email",
                        "/api/v1/users/code", "/api/v1/users/verify"
                ).permitAll()
                // 얼굴 인식 및 Rekognition 인증 관련
                .requestMatchers(
                        "/api/v1/rekognition/authentication",
                        "/api/v1/face/authentication",
                        "/api/v1/face/collection"
                ).permitAll()
                // 회의 정보 및 세션 조회 (비로그인 사용자도 접근 가능)
                .requestMatchers(
                        "/conferences", "/sessions",
                        "/api/v1/conference/**", "/api/v1/conferences/**",
                        "/api/v1/conferences/image/**"
                ).permitAll()
                // 예약 임시 저장 (비회원도 접근 허용 대상이라면)
                .requestMatchers("/api/v1/reservation/temp").permitAll()
                // Redis 테스트용 또는 캐시 데이터 확인용 엔드포인트
                .requestMatchers("/api/v1/redis").permitAll()
                // SSE 실시간 구독 관련 - 로그인 없이도 알림 구독 가능
                .requestMatchers("/api/v1/sse/subscribe", "/api/v1/sse/unsubscribe", "/api/v1/sse/last-count", "/api/v1/sse/status").permitAll()
                // 관리자 회원가입 (최초 관리자 등록 목적)
                .requestMatchers("/api/v1/admin/signup").permitAll()
                // 관리자 전용 회의 제어 API
                .requestMatchers("/api/v1/admin/conference/**").hasAuthority(Role.ADMIN.getRoleName())
                // 알림 조회는 누구나 가능
                .requestMatchers(HttpMethod.GET, "/api/v1/notices/**").permitAll()
                // 알림 등록/수정/삭제는 관리자만 가능
                .requestMatchers("/api/v1/notices/**").hasAuthority(Role.ADMIN.getRoleName())
                // OAuth2 로그인 (카카오 등)
                .requestMatchers("/oauth2/**", "/auth/login/kakao/**").permitAll()
                // 그 외 모든 요청은 인증 필요
                .anyRequest().authenticated()
        );

        // 세션 비활성화 - JWT 기반이므로 STATELESS로 설정
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // JWT 인증 필터 등록 (UsernamePasswordAuthenticationFilter 이전에 실행)
        http.addFilterBefore(new JwtAuthFilter(jwtUtil, objectMapper), UsernamePasswordAuthenticationFilter.class);

        // 인증/인가 실패 시 처리 핸들러 등록
        http.exceptionHandling(exception -> exception
                .accessDeniedHandler(new CustomAccessDeniedHandler(objectMapper))
                .authenticationEntryPoint(new CustomAuthenticationEntryPoint(objectMapper))
        );

        // OAuth2 로그인 설정 - provider 별 후처리 핸들러 지정
        http.oauth2Login(oauth2 -> oauth2
                .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserServiceFactory::loadUser))
                .successHandler((request, response, authentication) -> {
                    OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
                    String provider = token.getAuthorizedClientRegistrationId();
                    AuthenticationSuccessHandler handler = successHandlerFactory.getHandler(provider);
                    handler.onAuthenticationSuccess(request, response, authentication);
                })
        );

        return http.build();
    }

    public CorsConfigurationSource configurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.addAllowedHeader("*");
        configuration.addAllowedMethod("*");
        configuration.setAllowedOrigins(Arrays.asList("https://server.hyungjun-practice.store", "http://localhost:5173", "https://maskpass-6zo.vercel.app"));
        configuration.setAllowCredentials(true);
        configuration.addExposedHeader("ACCESS_TOKEN");
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration); // 모든 주소요청에 위 설정을 넣어주겠다.
        return source;
    }
}
