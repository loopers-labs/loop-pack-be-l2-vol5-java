package com.loopers;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.Environment;
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
        var application = new SpringApplication(CommerceApiApplication.class);
        application.addInitializers(context -> requireSafeRecoveryProfile(context.getEnvironment()));
        ConfigurableApplicationContext context = application.run(args);
        if (context.getEnvironment().acceptsProfiles(Profiles.of("benchmark-seed", "outbox-resume", "outbox-recover"))) {
            context.close();
        }
    }

    private static void requireSafeRecoveryProfile(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("outbox-recover"))
            && (!environment.acceptsProfiles(Profiles.of("payment-lab"))
                || environment.acceptsProfiles(Profiles.of("local", "test", "benchmark-seed", "outbox-resume"))
                || !"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                || !"never".equals(environment.getProperty("spring.sql.init.mode")))) {
            throw new IllegalStateException("Outbox 복구는 payment-lab,outbox-recover와 스키마 보존 설정이 필요합니다.");
        }
    }
}
