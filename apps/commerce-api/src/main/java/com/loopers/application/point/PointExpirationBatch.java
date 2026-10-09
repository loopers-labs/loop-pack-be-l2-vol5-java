package com.loopers.application.point;

import com.loopers.domain.point.PointBalanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PointExpirationBatch {
    private final PointBalanceRepository balances;
    private final PointExpirationWorker worker;
    private final Clock clock;

    public Result run() {
        ZonedDateTime cutoff = ZonedDateTime.now(clock);
        long cursor = 0;
        int succeeded = 0;
        int failed = 0;
        while (true) {
            List<Long> userIds = balances.findUserIdsAfter(cursor, 100);
            if (userIds.isEmpty()) {
                return new Result(succeeded, failed);
            }
            for (Long userId : userIds) {
                try {
                    worker.expire(userId, cutoff);
                    succeeded++;
                } catch (RuntimeException exception) {
                    failed++;
                    log.error("Point expiration failed: userId={}, cutoff={}", userId, cutoff, exception);
                }
                cursor = userId;
            }
        }
    }

    public record Result(int succeeded, int failed) {}
}
