package goorm.back.zo6.common.config;

import java.lang.reflect.Method;
import java.util.Arrays;
import lombok.extern.log4j.Log4j2;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.stereotype.Component;

@Log4j2
@Component
public class AsyncExceptionHandler implements AsyncUncaughtExceptionHandler {

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        log.error("[Async 예외] method={}, params={}, error={}",
                method.getName(), Arrays.toString(params), ex.getMessage(), ex);
    }
}
