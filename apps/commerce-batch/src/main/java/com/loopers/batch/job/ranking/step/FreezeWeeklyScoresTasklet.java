package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.RankingTime;
import com.loopers.batch.job.ranking.WeeklyRankingKey;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import com.loopers.batch.job.ranking.WeeklyRankingRevisionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 점수 고정. 한 트랜잭션 안에서 회차를 확인하고, 그 기간의 구매 활동으로 점수를 한 문장으로 넣고, 회차를 CALCULATING으로 남긴다.
 * 커밋하는 순간 이 회차의 점수가 고정된다. 이미 있는 회차면 점수를 다시 넣지 않는다.
 */
@Component
public class FreezeWeeklyScoresTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(FreezeWeeklyScoresTasklet.class);

    private final WeeklyRankingRepository repository;
    private final RankingTime rankingTime;

    public FreezeWeeklyScoresTasklet(WeeklyRankingRepository repository, RankingTime rankingTime) {
        this.repository = repository;
        this.rankingTime = rankingTime;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        StepExecution stepExecution = contribution.getStepExecution();
        WeeklyRankingKey key = WeeklyRankingKey.from(stepExecution.getJobParameters());
        LocalDate periodStart = key.period().start();
        int revision = key.revision();

        Optional<WeeklyRankingRevisionStatus> existing = repository.findStatus(periodStart, revision);
        if (existing.isPresent()) {
            keepExisting(key, existing.get());
            return RepeatStatus.FINISHED;
        }
        int lastRevision = repository.lastRevision(periodStart);
        if (revision != lastRevision + 1) {
            throw new IllegalStateException("회차는 그 기간의 마지막 회차 + 1이어야 한다. key=" + key + ", 마지막 회차=" + lastRevision);
        }
        if (repository.countCalculating(periodStart) > 0) {
            throw new IllegalStateException("그 기간에 계산 중인 회차가 있다. 그 회차를 끝내거나 닫은 뒤 시작한다. key=" + key);
        }
        long invalidActivities = repository.countInvalidActivities(key.period());
        if (invalidActivities > 0) {
            throw new IllegalStateException("기간 원본에 수량이 0 이하인 구매 활동이 있다. key=" + key + ", count=" + invalidActivities);
        }

        int products = repository.insertScores(key.period(), revision);
        // 넣은 뒤에 읽어야 "이 시각 뒤에 도착한 구매는 결과에 없다"가 지켜진다.
        LocalDateTime calculatedAt = rankingTime.nowUtc();
        repository.insertCalculatingRevision(periodStart, revision, calculatedAt, stepExecution.getJobExecutionId());

        contribution.incrementWriteCount(products);
        log.info("점수를 고정했다. key={}, products={}", key, products);
        return RepeatStatus.FINISHED;
    }

    private void keepExisting(WeeklyRankingKey key, WeeklyRankingRevisionStatus status) {
        switch (status) {
            case CANCELLED -> throw new IllegalStateException("닫은 회차는 다시 계산하지 않는다. key=" + key);
            case CALCULATING -> log.info("점수가 이미 고정돼 있어 그대로 쓴다. key={}", key);
            case COMPLETED -> log.info("이미 완성된 회차라 아무것도 쓰지 않는다. key={}", key);
        }
    }
}
