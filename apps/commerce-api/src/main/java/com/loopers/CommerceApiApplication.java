package com.loopers;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import java.util.TimeZone;

@ConfigurationPropertiesScan
// 인증 사용자는 관리자 경계(AdminBoundaryConfig)가 직접 만든다. 쓰지 않는 Boot 기본 사용자와 생성 비밀번호 WARN이 생기지 않게 제외한다.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class CommerceApiApplication {

    @PostConstruct
    public void started() {
        // set timezone
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        SpringApplication.run(CommerceApiApplication.class, args);
    }
}
