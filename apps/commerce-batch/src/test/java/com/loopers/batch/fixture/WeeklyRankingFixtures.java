package com.loopers.batch.fixture;

import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

import java.time.LocalDate;

/**
 * 발제 §6 fixture와 job parameter. 2026-08-17 주 = [2026-08-17T00:00Z, 2026-08-24T00:00Z).
 * 기대: 상품 7 = 5점·1위, 9 = 5점·2위, 8 = 1점·3위. 원본 4행, reader item 3개.
 */
public final class WeeklyRankingFixtures {

    public static final LocalDate WEEK = LocalDate.parse("2026-08-17");
    public static final String R1 = "weekly-20260817-r1";
    public static final String R2 = "weekly-20260817-r2";
    /** 7·9·8 순위의 결과 checksum — printf '7\t5\t1\n9\t5\t2\n8\t1\t3\n' | shasum -a 256 */
    public static final String R1_RESULT_CHECKSUM = "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2";

    private WeeklyRankingFixtures() {}

    public static TestProductPurchaseActivity[] lecturePurchases() {
        return new TestProductPurchaseActivity[]{
            TestProductPurchaseActivity.of(42L, 7L, 2, "2026-08-17T01:00:00Z"),
            TestProductPurchaseActivity.of(42L, 8L, 1, "2026-08-17T01:00:00Z"),
            TestProductPurchaseActivity.of(43L, 7L, 3, "2026-08-18T01:00:00Z"),
            TestProductPurchaseActivity.of(44L, 9L, 5, "2026-08-19T01:00:00Z")
        };
    }

    /** 고정 뒤 같은 주에 늦게 도착한 구매: 상품 8을 10개 → r2에서는 8 = 11점·1위 */
    public static TestProductPurchaseActivity latePurchase() {
        return TestProductPurchaseActivity.of(46L, 8L, 10, "2026-08-20T01:00:00Z");
    }

    public static JobParameters params(LocalDate periodStart, long revision) {
        return new JobParametersBuilder()
            .addLocalDate("periodStart", periodStart)
            .addLong("revision", revision)
            .toJobParameters();
    }

    /**
     * 실행 안의 step 하나. 테스트 클래스에 StepExecution을 돌려주는 메서드를 두면 @SpringBatchTest가 step 실행 생성 메서드로 오인하므로 여기에 둔다.
     */
    public static StepExecution stepOf(JobExecution execution, String stepName) {
        return execution.getStepExecutions().stream()
            .filter(step -> step.getStepName().equals(stepName))
            .findFirst()
            .orElseThrow();
    }
}
