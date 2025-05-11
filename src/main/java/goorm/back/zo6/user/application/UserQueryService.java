package goorm.back.zo6.user.application;

import goorm.back.zo6.user.dto.response.UserResponse;

public interface UserQueryService {
    UserResponse findById(Long id);
    UserResponse findByEmail(String email);
    boolean isEmailAvailable(String email);
}
