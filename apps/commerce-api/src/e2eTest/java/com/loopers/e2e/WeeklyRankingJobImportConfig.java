package com.loopers.e2e;

import com.loopers.batch.job.ranking.RankingSnapshotStore;
import com.loopers.batch.job.ranking.WeeklyRankingJobConfig;
import com.loopers.batch.job.ranking.step.FreezeWeeklyInputTasklet;
import com.loopers.batch.job.ranking.step.PublishWeeklyRankingTasklet;
import com.loopers.batch.job.ranking.step.WeeklyScoreProcessor;
import com.loopers.batch.job.ranking.step.WeeklyScoreWriter;
import com.loopers.batch.listener.JobListener;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * E2E context에 commerce-batch의 실제 weeklyRanking job 부품만 명시해 연결한다 (docs/week9/design.md D7).
 * batch 클래스는 {@link ExcludeBatchFromScan}으로 component scan에서 모두 빠지고, 여기 적은 것만 올라온다.
 * job 구성은 {@code spring.batch.job.name=weeklyRanking}일 때만 켜지므로 테스트가 그 값을 준다. 시계는 commerce-api의 Clock bean을 쓴다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({
    WeeklyRankingJobConfig.class,
    FreezeWeeklyInputTasklet.class,
    WeeklyScoreProcessor.class,
    WeeklyScoreWriter.class,
    PublishWeeklyRankingTasklet.class,
    RankingSnapshotStore.class,
    JobListener.class
})
public class WeeklyRankingJobImportConfig {
}
