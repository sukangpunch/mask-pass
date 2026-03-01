//package goorm.back.zo6.user.application;
//
//import goorm.back.zo6.common.exception.CustomException;
//import goorm.back.zo6.common.exception.ErrorCode;
//import jakarta.annotation.PostConstruct;
//import lombok.RequiredArgsConstructor;
//import net.nurigo.sdk.NurigoApp;
//import net.nurigo.sdk.message.model.Message;
//import net.nurigo.sdk.message.request.SingleMessageSendingRequest;
//import net.nurigo.sdk.message.service.DefaultMessageService;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.scheduling.annotation.Async;
//import org.springframework.stereotype.Service;
//import org.springframework.transaction.annotation.Transactional;
//
//import java.time.Duration;
//import java.util.Random;
/**
 * 이번 리팩토링에서는 사용하지 않을 예정
 */
//@Service
//@RequiredArgsConstructor
//public class PhoneValidService {
//
//    private final RedisTemplate<String, String> redisTemplate;
//    private DefaultMessageService defaultMessageService;
//
//    @PostConstruct
//    public void init() {
//        defaultMessageService = NurigoApp.INSTANCE.initialize(apiKey, apiSecretKey, provider);
//    }
//
//    @Value("${send.number}")
//    private String number;
//
//
//    @Value("${send.key}")
//    private String apiKey;
//
//
//    @Value("${send.secret}")
//    private String apiSecretKey;
//
//    @Value("${send.provider}")
//    private String provider;
//
//    @Transactional
//    @Async("customTaskExecutor")
//    public void sendValidMessage(String phone) {
//        String code = String.valueOf(generateRandomNumber());
//        redisTemplate.opsForValue().set(phone, code, Duration.ofMinutes(3));
//        Message sms = new Message();
//        sms.setFrom(number);
//        sms.setTo(phone);
//        sms.setText("maskPass : " + code);
//        defaultMessageService.sendOne(new SingleMessageSendingRequest(sms));
//    }
//
//    public boolean validPhone(String phone, String code) {
//        String generatedCode = redisTemplate.opsForValue().get(phone);
//        if (generatedCode == null) {
//            throw new CustomException(ErrorCode.EXPIRED_PHONE);
//        }
//
//        boolean isValid = generatedCode.equals(code);
//
//        if (isValid) {
//            redisTemplate.opsForValue().set(phone + ":verified", "true", Duration.ofMinutes(3));
//        }
//
//        return isValid;
//    }
//
//    public boolean isPhoneAlreadyVerified(String phone) {
//        return Boolean.TRUE.equals(redisTemplate.hasKey(phone + ":verified"));
//    }
//
//    public void removeVerifiedPhone(String phone) {
//        redisTemplate.delete(phone + ":verified");
//    }
//
//    private int generateRandomNumber() {
//        Random random = new Random();
//        return 100000 + random.nextInt(900000);
//    }
//}
