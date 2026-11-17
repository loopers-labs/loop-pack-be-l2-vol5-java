package com.loopers.job.ranking.process;

import com.loopers.CommerceBatchApplication;
import com.loopers.batch.job.weeklyranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.weeklyranking.WeeklyRankingLaunchRunner;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 실제 연결 테스트가 띄우는 batch 자식 JVM 의 진입점(랭킹 설계 4.12·11.5). 제품 main 과 같은 공통 시작 경로를 쓰고,
 * 종료 전에 이번 실행의 계산 Step 영속 checkpoint 를 JobExplorer 로 다시 읽어 stdout 에 남긴다(랭킹 설계 5.7).
 */
public final class WeeklyRankingTestProcessMain {

    private static final String READ_COUNT_KEY = WeeklyRankingJobConfig.PRODUCT_READER + ".read.count";

    private WeeklyRankingTestProcessMain() {}

    public static void main(String[] args) {
        ConfigurableApplicationContext context =
            CommerceBatchApplication.createApplication(WeeklyRankingFaultSupport.class).run(args);
        try {
            context.getBeanProvider(WeeklyRankingLaunchRunner.class)
                .ifAvailable(runner -> printCheckpoint(context.getBean(JobExplorer.class), runner.getResult().executionId()));
        } catch (RuntimeException failure) {
            // 관찰이 실패해도 context 를 닫고, 예외로 끝내 부모가 0 이 아닌 종료 코드를 보게 한다.
            SpringApplication.exit(context);
            throw failure;
        }
        System.exit(SpringApplication.exit(context));
    }

    private static void printCheckpoint(JobExplorer explorer, Long executionId) {
        JobExecution execution = executionId == null ? null : explorer.getJobExecution(executionId);
        if (execution == null) {
            return;
        }
        for (StepExecution step : execution.getStepExecutions()) {
            if (step.getStepName().equals(WeeklyRankingJobConfig.CALCULATE_STEP)) {
                var context = explorer.getStepExecution(executionId, step.getId()).getExecutionContext();
                System.out.println("WEEKLY_RANKING_CHECKPOINT executionId=" + executionId + " step=" + step.getStepName()
                    + " readCount=" + (context.containsKey(READ_COUNT_KEY) ? context.getInt(READ_COUNT_KEY) : "none"));
            }
        }
    }
}
