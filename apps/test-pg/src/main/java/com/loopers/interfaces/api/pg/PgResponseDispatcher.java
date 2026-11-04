package com.loopers.interfaces.api.pg;

import com.loopers.domain.pg.model.PgScenario;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

/** 지연·유실은 DB 저장 이후 HTTP 전달에만 적용한다. */
@Component
public class PgResponseDispatcher {
    private final TaskScheduler scheduler;

    public PgResponseDispatcher(@Qualifier("pgResponseScheduler") TaskScheduler scheduler) {
        this.scheduler = scheduler;
    }

    public DeferredResult<ResponseEntity<?>> dispatch(List<Condition> conditions, ResponseEntity<?> response) {
        DeferredResult<ResponseEntity<?>> result = new DeferredResult<>(31000L);
        boolean drop = conditions.stream().anyMatch(condition -> condition.mode() == PgScenario.ResponseMode.DROP);
        long delay = drop ? 30000 : conditions.stream().mapToLong(Condition::delayMs).max().orElse(0);
        if (delay == 0) {
            result.setResult(response);
        } else {
            scheduler.schedule(() -> result.setResult(drop ? ResponseEntity.status(504).build() : response),
                    Instant.now().plusMillis(delay));
        }
        return result;
    }

    public record Condition(PgScenario.ResponseMode mode, long delayMs) {}
}
