package com.loopers.batch.job.ranking;

import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

public class PublishRankingTasklet implements Tasklet {
    public static final String RESULT_CHECKSUM_KEY = "resultChecksum";
    public static final String NEWER_PUBLISHED = "NEWER_PUBLISHED";

    private final WeeklyRankingRepository weeklyRankingRepository;
    private final String snapshotId;
    private final int rankingSize;

    public PublishRankingTasklet(WeeklyRankingRepository weeklyRankingRepository, String snapshotId, int rankingSize) {
        this.weeklyRankingRepository = weeklyRankingRepository;
        this.snapshotId = snapshotId;
        this.rankingSize = rankingSize;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        return RepeatStatus.FINISHED;
    }
}
