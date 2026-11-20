package com.loopers;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.core.env.Profiles;

import java.util.TimeZone;

@ConfigurationPropertiesScan
@SpringBootApplication
public class CommerceBatchApplication {

    @PostConstruct
    public void started() {
        // set timezone
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    }

    public static void main(String[] args) {
        var context = SpringApplication.run(CommerceBatchApplication.class, args);
        var environment = context.getEnvironment();
        if (environment.acceptsProfiles(Profiles.of("ranking-scheduler"))
            && "resident".equals(environment.getProperty("ranking.scheduler.mode", "resident"))) {
            return;
        }
        int exitCode = SpringApplication.exit(context);
        System.exit(exitCode);
    }
}
