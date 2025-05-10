package goorm.back.zo6.user.application;

import goorm.back.zo6.user.dto.request.SignUpRequest;
import goorm.back.zo6.user.dto.response.SignUpResponse;

public interface UserPhoneSignUpService {
    SignUpResponse signUpWithPhone(SignUpRequest request);
}
