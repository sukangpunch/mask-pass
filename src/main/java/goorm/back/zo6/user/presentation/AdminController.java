package goorm.back.zo6.user.presentation;

import goorm.back.zo6.common.dto.ResponseDto;
import goorm.back.zo6.user.application.UserSignUpService;
import goorm.back.zo6.user.dto.request.SignUpRequest;
import goorm.back.zo6.user.dto.response.SignUpResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "admin", description = "Admin API")
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserSignUpService userSignUpService;

    @PostMapping("/signup")
    @Operation(summary = "관리자 회원가입", description = "관리자를 등록합니다.")
    public ResponseEntity<ResponseDto<SignUpResponse>> adminSignUp(@RequestBody SignUpRequest request) {
        return ResponseEntity.ok().body(ResponseDto.of(userSignUpService.adminSignUp(request)));
    }
}
