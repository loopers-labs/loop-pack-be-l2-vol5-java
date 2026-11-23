package com.loopers;

import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchContextInitializer;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.List;
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
        int exitCode = SpringApplication.exit(createApplication().run(args));
        System.exit(exitCode);
    }

    /** 제품·테스트 main 이 같은 초기화 검사를 거치도록 SpringApplication 조립을 한 곳에 둔다(랭킹 설계 4.12). */
    public static SpringApplication createApplication(Class<?>... additionalSources) {
        SpringApplication application = new SpringApplication(CommerceBatchApplication.class);
        application.addPrimarySources(List.of(additionalSources));
        application.addInitializers(new WeeklyRankingLaunchContextInitializer());
        return application;
    }
}
