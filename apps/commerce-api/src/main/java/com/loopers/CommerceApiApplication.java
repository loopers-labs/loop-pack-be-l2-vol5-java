package com.loopers;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import java.util.TimeZone;

@ConfigurationPropertiesScan
@SpringBootApplication
public class CommerceApiApplication {

    @PostConstruct
    public void started() {
        // JVM 기본 시간대를 Asia/Seoul로 맞춘다.
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(CommerceApiApplication.class, args);
    }
}
