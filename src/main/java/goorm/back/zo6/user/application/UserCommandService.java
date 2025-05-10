package goorm.back.zo6.user.application;

public interface UserCommandService {
    void deactivateByToken(String token);
    void initPhoneNumber(String email, String phone);
}
