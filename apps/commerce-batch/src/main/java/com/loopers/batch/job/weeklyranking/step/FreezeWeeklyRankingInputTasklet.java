package com.loopers.batch.job.weeklyranking.step;

import com.loopers.application.ranking.RankingInputService;
import com.loopers.batch.job.weeklyranking.WeeklyRankingParameters;
import com.loopers.domain.ranking.RankingInputManifest;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/** Step TX 안에서 입력을 고정하거나 기존 입력을 검사해 재사용한다. */
@RequiredArgsConstructor
public class FreezeWeeklyRankingInputTasklet implements Tasklet {

    private final RankingInputService inputService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        var stepExecution = contribution.getStepExecution();
        RankingInputManifest manifest = inputService.freezeOrReuseInput(
            WeeklyRankingParameters.parse(stepExecution.getJobParameters()));
        stepExecution.getExecutionContext().putString("snapshotId", manifest.snapshotId());
        stepExecution.getExecutionContext().putString("inputChecksum", manifest.inputChecksum());
        return RepeatStatus.FINISHED;
    }
}
