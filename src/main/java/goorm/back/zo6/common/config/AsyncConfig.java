package goorm.back.zo6.common.config;

import java.util.Collections;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.slf4j.MDC;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Log4j2
@RequiredArgsConstructor
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    private final AsyncExceptionHandler asyncExceptionHandler;

    private static final TaskDecorator MDC_TASK_DECORATOR = runnable -> {
        Map<String, String> contextMap = MDC.getCopyOfContextMap();
        return () -> {
            MDC.setContextMap(contextMap != null ? contextMap : Collections.emptyMap());
            try {
                runnable.run();
            } finally {
                MDC.clear();
            }
        };
    };

    @Bean(name = "customTaskExecutor")
    public ThreadPoolTaskExecutor customTaskExecutor() {
        int processors = Runtime.getRuntime().availableProcessors();
        int corePoolSize = Math.max(2, processors);
        int maxPoolSize = Math.max(4, processors * 3); // Ec2 프리티어 코어와 논리 코어는 둘다 1코어, 최소한의 성능 유지
        int queueCapacity = 50;
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);  // 기본 쓰레드 수
        executor.setMaxPoolSize(maxPoolSize);   // 최대 쓰레드 수
        executor.setQueueCapacity(queueCapacity); // 요청 대기 수
        executor.setThreadNamePrefix("event-Async-");
        executor.setTaskDecorator(MDC_TASK_DECORATOR);
        executor.initialize();
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return asyncExceptionHandler;
    }
}
