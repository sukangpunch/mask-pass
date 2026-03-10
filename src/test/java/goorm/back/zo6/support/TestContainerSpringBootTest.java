package goorm.back.zo6.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ContextConfiguration;
import org.testcontainers.junit.jupiter.Testcontainers;

@ComponentScan(basePackages = "goorm.back.zo6")
@ExtendWith({DatabaseClearExtension.class})
@ContextConfiguration(initializers = {RedisTestContainer.class, PostgresTestContainer.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TestContainerSpringBootTest {

}
