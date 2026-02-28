package goorm.back.zo6;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableJpaAuditing
public class Zo6Application {

    public static void main(String[] args) {
        SpringApplication.run(Zo6Application.class, args);
    }

}
