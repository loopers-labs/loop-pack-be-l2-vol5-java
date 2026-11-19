package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.RankStats;
import com.loopers.batch.job.ranking.WeeklyRankingKey;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import com.loopers.batch.job.ranking.WeeklyRankingRevisionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 공개. 한 트랜잭션 안에서 순위가 빠짐없이 매겨졌는지 확인하고, 결과 checksum을 채워 회차를 COMPLETED로 바꾼다.
 * 커밋하는 순간 이 회차가 그 기간의 공개본이 된다. 이미 완성된 회차면 아무것도 쓰지 않는다.
 */
@Component
public class PublishWeeklyRankingTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(PublishWeeklyRankingTasklet.class);

    private final WeeklyRankingRepository repository;

    public PublishWeeklyRankingTasklet(WeeklyRankingRepository repository) {
        this.repository = repository;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        WeeklyRankingKey key = WeeklyRankingKey.from(contribution.getStepExecution().getJobParameters());
        LocalDate periodStart = key.period().start();
        int revision = key.revision();

        WeeklyRankingRevisionStatus status = repository.findStatus(periodStart, revision)
            .orElseThrow(() -> new IllegalStateException("회차 정보가 없다. 점수 고정이 먼저다. key=" + key));
        if (status == WeeklyRankingRevisionStatus.CANCELLED) {
            throw new IllegalStateException("닫은 회차는 공개하지 않는다. key=" + key);
        }
        if (status == WeeklyRankingRevisionStatus.COMPLETED) {
            log.info("이미 완성된 회차라 아무것도 쓰지 않는다. key={}", key);
            return RepeatStatus.FINISHED;
        }

        RankStats stats = repository.rankStats(periodStart, revision);
        if (!stats.isComplete()) {
            throw new IllegalStateException("순위가 빠짐없이 매겨지지 않아 공개하지 않는다. key=" + key + ", stats=" + stats);
        }
        String resultChecksum = repository.resultChecksum(periodStart, revision);
        if (repository.complete(periodStart, revision, resultChecksum) != 1) {
            throw new IllegalStateException("완성하기 전에 회차가 계산 중에서 다른 상태로 바뀌었다. key=" + key);
        }
        contribution.incrementWriteCount(1);
        log.info("회차를 공개했다. key={}, products={}", key, stats.rows());
        return RepeatStatus.FINISHED;
    }
}
