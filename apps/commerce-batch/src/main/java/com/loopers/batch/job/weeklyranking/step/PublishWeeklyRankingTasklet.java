package com.loopers.batch.job.weeklyranking.step;

import com.loopers.application.ranking.RankingPublicationService;
import com.loopers.batch.job.weeklyranking.WeeklyRankingParameters;
import com.loopers.domain.ranking.PublicationResult;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.repeat.RepeatStatus;

/** 세 공개 결과(PUBLISHED·ALREADY_PUBLISHED·SKIPPED_NEWER) 모두 정상 완료이며 결과를 Step context 에 남긴다. */
@RequiredArgsConstructor
public class PublishWeeklyRankingTasklet implements Tasklet {

    public static final String OUTCOME_KEY = "weeklyRanking.publicationOutcome";
    public static final String TARGET_KEY = "weeklyRanking.targetSnapshotId";
    public static final String PUBLISHED_KEY = "weeklyRanking.publishedSnapshotId";

    private final RankingPublicationService publicationService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var stepExecution = contribution.getStepExecution();
        PublicationResult result = publicationService.publish(
            WeeklyRankingParameters.parse(stepExecution.getJobParameters()));
        ExecutionContext context = stepExecution.getExecutionContext();
        context.putString(OUTCOME_KEY, result.outcome().name());
        context.putString(TARGET_KEY, result.targetSnapshotId());
        context.putString(PUBLISHED_KEY, result.publishedSnapshotId());
        return RepeatStatus.FINISHED;
    }
}
