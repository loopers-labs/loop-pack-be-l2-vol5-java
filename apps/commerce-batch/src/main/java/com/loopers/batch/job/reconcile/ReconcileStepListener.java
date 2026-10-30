package com.loopers.batch.job.reconcile;

import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

// step이 끝까지 성공했을 때만 워터마크를 구간 끝으로 옮기고, 성공·실패와 무관하게 요약 한 줄을 남긴다.
@Slf4j
public class ReconcileStepListener implements StepExecutionListener {
    private final ReconcileRun run;
    private final ReconcileCheckpointRepository checkpointRepository;
    private final Clock clock;

    public ReconcileStepListener(ReconcileRun run, ReconcileCheckpointRepository checkpointRepository, Clock clock) {
        this.run = run;
        this.checkpointRepository = checkpointRepository;
        this.clock = clock;
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        boolean completed = stepExecution.getStatus() == BatchStatus.COMPLETED;
        if (completed) {
            checkpointRepository.save(run.window().end(), clock.instant());
        }
        String failure = stepExecution.getFailureExceptions().stream().findFirst()
            .map(e -> ", 실패=" + e.getMessage()).orElse("");
        log.info("[대사 요약] 구간={}, 확정 주문={}건, 누락 orderId={}, 명령 send={}건 성공{}, 워터마크 {} → {}, 결과={}",
            run.window(), run.confirmedOrders(), run.missingOrderIds(), run.sentCommands(), failure,
            run.before().map(ReconcileCheckpoint::toString).orElse("없음"),
            checkpointRepository.find().map(ReconcileCheckpoint::toString).orElse("없음"), stepExecution.getStatus());
        return null;
    }
}
