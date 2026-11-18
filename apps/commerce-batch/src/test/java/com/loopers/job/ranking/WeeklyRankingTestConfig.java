package com.loopers.job.ranking;

import com.loopers.batch.job.ranking.ProductScore;
import com.loopers.batch.job.ranking.SnapshotInfo;
import com.loopers.batch.job.ranking.SourcePurchase;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import com.loopers.job.reconcile.ReconcileTestConfig.MutableClock;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

// 주간 랭킹 Job 테스트 전용 대역. 운영 코드에는 분기를 두지 않고 저장소를 감싸 실패·대기를 주입한다.
@TestConfiguration
public class WeeklyRankingTestConfig {
    @Bean
    @Primary
    MutableClock weeklyRankingTestClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    HookedWeeklyRankingRepository hookedWeeklyRankingRepository(JdbcTemplate jdbcTemplate) {
        return new HookedWeeklyRankingRepository(jdbcTemplate);
    }

    public static class HookedWeeklyRankingRepository extends WeeklyRankingRepository {
        public static final String INFO_FAILURE = "주입된 snapshot 정보 저장 실패";
        public static final String SCORE_FAILURE = "주입된 점수 계산 실패";
        public static final String PUBLISH_FAILURE = "주입된 pointer 공개 실패";

        private final AtomicBoolean failNextInfo = new AtomicBoolean();
        private final AtomicBoolean failNextPublish = new AtomicBoolean();
        private final AtomicReference<Long> failScoreOfProduct = new AtomicReference<Long>();
        private final AtomicReference<Runnable> afterSourceRead = new AtomicReference<Runnable>(() -> { });

        HookedWeeklyRankingRepository(JdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        // 다음 한 번의 snapshot 정보 INSERT를 실제로 하지 않고 실패시킨다(입력 INSERT는 이미 같은 TX에서 나간 뒤다).
        public void failNextInfoInsert() {
            failNextInfo.set(true);
        }

        // 그 상품의 점수 쓰기를 한 번 실패시킨다.
        public void failScoreOnce(long productId) {
            failScoreOfProduct.set(productId);
        }

        // 원본을 읽은 직후(같은 TX 안, 입력 INSERT 전) 실행한다.
        public void afterSourceRead(Runnable action) {
            afterSourceRead.set(action);
        }

        // 다음 한 번의 pointer 공개를 실제로 하지 않고 실패시킨다(완성 랭킹 INSERT는 이미 같은 TX에서 나간 뒤다).
        public void failNextPublish() {
            failNextPublish.set(true);
        }

        public void disarm() {
            failNextInfo.set(false);
            failNextPublish.set(false);
            failScoreOfProduct.set(null);
            afterSourceRead.set(() -> { });
        }

        @Override
        public List<SourcePurchase> readSource(LocalDate periodStart, LocalDate periodEnd) {
            List<SourcePurchase> rows = super.readSource(periodStart, periodEnd);
            afterSourceRead.get().run();
            return rows;
        }

        @Override
        public void insertInfo(SnapshotInfo info) {
            if (failNextInfo.getAndSet(false)) {
                throw new IllegalStateException(INFO_FAILURE);
            }
            super.insertInfo(info);
        }

        @Override
        public boolean publish(LocalDate periodStart, String snapshotId) {
            if (failNextPublish.getAndSet(false)) {
                throw new IllegalStateException(PUBLISH_FAILURE);
            }
            return super.publish(periodStart, snapshotId);
        }

        // reader가 합계까지 읽으므로(D159) 그 상품이 든 점수 chunk를 쓰기 전에 실패시킨다. 그 chunk는 통째로 rollback된다.
        @Override
        public void upsertScores(List<ProductScore> scores) {
            Long failing = failScoreOfProduct.get();
            if (failing != null && scores.stream().anyMatch(score -> score.productId() == failing)) {
                failScoreOfProduct.set(null);
                throw new IllegalStateException(SCORE_FAILURE + " product=" + failing);
            }
            super.upsertScores(scores);
        }
    }
}
