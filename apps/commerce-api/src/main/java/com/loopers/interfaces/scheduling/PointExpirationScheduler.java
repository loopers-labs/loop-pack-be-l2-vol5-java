package com.loopers.interfaces.scheduling;

import com.loopers.application.point.PointExpirationBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "point.expiration.scheduling-enabled", havingValue = "true")
public class PointExpirationScheduler {
    private final PointExpirationBatch batch;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void expire() {
        log.info("Point expiration finished: {}", batch.run());
    }
}
