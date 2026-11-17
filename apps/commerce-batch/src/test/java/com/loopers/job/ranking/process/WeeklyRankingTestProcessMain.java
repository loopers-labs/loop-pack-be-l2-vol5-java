package com.loopers.job.ranking.process;

import com.loopers.CommerceBatchApplication;
import org.springframework.boot.SpringApplication;

/** 실제 연결 테스트가 띄우는 batch 자식 JVM 의 진입점(랭킹 설계 4.12·11.5). 제품 main 과 같은 공통 시작 경로를 쓴다. */
public final class WeeklyRankingTestProcessMain {

    private WeeklyRankingTestProcessMain() {}

    public static void main(String[] args) {
        int exitCode = SpringApplication.exit(
            CommerceBatchApplication.createApplication(WeeklyRankingFaultSupport.class).run(args));
        System.exit(exitCode);
    }
}
