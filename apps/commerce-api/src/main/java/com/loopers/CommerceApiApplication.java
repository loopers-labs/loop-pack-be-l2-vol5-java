package com.loopers;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

@ConfigurationPropertiesScan
@SpringBootApplication
@EnableScheduling
// 스프링 부트 애플리케이션 진입점
public class CommerceApiApplication {

    // 타임존 초기화
    @PostConstruct
    public void started() {
        // set timezone
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(CommerceApiApplication.class, args);
        if (context.getEnvironment().acceptsProfiles(Profiles.of("benchmark-seed"))) {
            context.close();
        }
    }
}
