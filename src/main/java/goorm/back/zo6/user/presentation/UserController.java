package goorm.back.zo6.user.presentation;

import goorm.back.zo6.auth.domain.LoginUser;
import goorm.back.zo6.common.dto.ResponseDto;
import goorm.back.zo6.user.application.*;
import goorm.back.zo6.user.dto.request.EmailRequest;
import goorm.back.zo6.user.dto.request.PhoneRequest;
import goorm.back.zo6.user.dto.request.PhoneValidRequest;
import goorm.back.zo6.user.dto.request.SignUpRequest;
import goorm.back.zo6.user.dto.response.SignUpResponse;
import goorm.back.zo6.user.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Tag(name = "user", description = "User API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final PhoneValidService phoneValidService;
    private final UserSignUpService userSignUpService;
    private final UserPhoneSignUpService userPhoneSignUpService;
    private final UserQueryService userQueryService;
    private final UserCommandService userCommandService;

    @GetMapping("/{userId}")
    @Operation(summary = "id 유저 조회", description = "유저 id로 유저 정보를 조회합니다.")
    public ResponseEntity<ResponseDto<UserResponse>> getUserById(@PathVariable("userId") Long userId){
        return ResponseEntity.ok().body(ResponseDto.of(userQueryService.findById(userId)));
    }

    @GetMapping
    @Operation(summary = "토큰 유저 조회", description = "유저 토큰으로 유저 정보를 조회합니다.")
    public ResponseEntity<ResponseDto<UserResponse>> getUserByToken(@AuthenticationPrincipal LoginUser loginUser){
        String email = loginUser.getUsername();
        return ResponseEntity.ok().body(ResponseDto.of(userQueryService.findByEmail(email)));
    }

    @PostMapping("/signup")
    @Operation(summary = "회원가입(테스트용)", description = "회원가입 정보를 통해 유저를 생성 및 등록 합니다.")
    public ResponseEntity<ResponseDto<SignUpResponse>> signUp(
            @Validated @RequestBody SignUpRequest request) {
        return ResponseEntity.ok().body(ResponseDto.of(userSignUpService.signUp(request)));
    }

    @PostMapping("/signup-link")
    @Operation(summary = "회원가입-임시 예매 링크", description = "회원가입 시 임시 예매와 링크 됩니다.")
    public ResponseEntity<ResponseDto<SignUpResponse>> signUpLink(@Validated @RequestBody SignUpRequest request) {
        return ResponseEntity.ok().body(ResponseDto.of(userPhoneSignUpService.signUpWithPhone(request)));
    }

    @PutMapping("/phone")
    @Operation(summary = "소셜 로그인 유저 전화번호 등록", description = "소셜 로그인 유저의 전화번호를 등록합니다.")
    public ResponseEntity<String> updatePhone(@AuthenticationPrincipal LoginUser loginUser, @Validated @RequestBody PhoneRequest request) {
        userCommandService.initPhoneNumber(loginUser.getUsername(), request.phone());
        return ResponseEntity.ok().body("소셜 로그인 유저 전화번호 등록 완료");
    }

    @DeleteMapping
    @Operation(summary = "유저 논리 탈퇴", description = "유저 토큰으로 유저를 논리 탈퇴(비활성화) 합니다.")
    public ResponseEntity<ResponseDto<String>> deactivateByToken(@AuthenticationPrincipal LoginUser loginUser) {
        String email = loginUser.getUsername();
        userCommandService.deactivateByToken(email);
        return ResponseEntity.ok().body(ResponseDto.of("성공적으로 회원 탈퇴하였습니다."));
    }
    @PostMapping("/code")
    @Operation(summary = "전화번호 인증 문자 전송", description = "해당 전화번호에 인증 번호를 전송합니다.")
    public ResponseEntity<String> sendMessage(@Valid @RequestBody PhoneRequest request) {
        phoneValidService.sendValidMessage(request.phone());
        return ResponseEntity.ok().body("인증 번호 전송 완료");
    }

    @PostMapping("/verify")
    @Operation(summary = "전화번호 검증", description = "해당 전화번호에 인증 번호를 검증합니다.")
    public ResponseEntity<Boolean> verifyCode(@Valid @RequestBody PhoneValidRequest request) {
        return ResponseEntity.ok().body(phoneValidService.validPhone(request.phone(), request.code()));
    }

    @PostMapping("/check-email")
    @Operation(summary = "이메일 중복 확인",description = "입력한 이메일의 중복을 검증합니다.")
    public ResponseEntity<Boolean> verifyEmail(@Valid @RequestBody EmailRequest request){
        return ResponseEntity.ok().body(userQueryService.isEmailAvailable(request.email()));
    }
}
