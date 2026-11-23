package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.WeeklyRankingKey;
import com.loopers.batch.job.ranking.WeeklyRankingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.stereotype.Component;

/**
 * 순위 Step이 시작할 때 계산 중인 그 회차의 순위를 비운다. 다시 실행해도 이어 가지 않고 처음부터 매기기 위해서다.
 * 비우지 않고 덮어쓰면, 순위 규칙이 바뀐 경우 남아 있던 순위와 유일 제약이 부딪힌다.
 */
@Component
public class ClearRanksListener implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(ClearRanksListener.class);

    private final WeeklyRankingRepository repository;

    public ClearRanksListener(WeeklyRankingRepository repository) {
        this.repository = repository;
    }

    @Override
    public void beforeStep(StepExecution stepExecution) {
        WeeklyRankingKey key = WeeklyRankingKey.from(stepExecution.getJobParameters());
        int cleared = repository.clearRanks(key.period().start(), key.revision());
        log.info("순위를 비우고 처음부터 매긴다. key={}, cleared={}", key, cleared);
    }
}
