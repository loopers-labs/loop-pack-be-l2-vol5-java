package com.loopers.infrastructure.scheduler.pg;

import com.loopers.application.pg.usecase.CompleteDuePgPaymentsUseCase;
import com.loopers.application.pg.gateway.PgCallbackGateway;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pg.processing.enabled", havingValue = "true", matchIfMissing = true)
public class PgCompletionScheduler {
    private final CompleteDuePgPaymentsUseCase complete;
    private final PgCallbackGateway callback;

    @Scheduled(fixedDelayString = "${pg.processing.interval-ms:100}", scheduler = "pgProcessingScheduler")
    public void completeDue() {
        // 별도 Bean의 @Transactional 호출이 반환되면 종결 commit이 완료된 상태다.
        complete.execute(Instant.now()).forEach(callback::publish);
    }
}
