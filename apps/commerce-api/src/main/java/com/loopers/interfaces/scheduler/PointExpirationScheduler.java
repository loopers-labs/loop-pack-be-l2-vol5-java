package com.loopers.interfaces.scheduler;

import com.loopers.application.point.PointExpirationFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;

/**
 * 포인트 만료 처리를 매일 시작한다 (ADR-10). 언제 실행할지만 알고, 무엇을 어떻게 만료할지는 Facade와 PointGroup이 안다.
 * 실행 시각은 point.expiration.cron으로 바꿀 수 있다. 기본값은 요청이 적은 새벽 4시(서울).
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PointExpirationScheduler {

    private final PointExpirationFacade pointExpirationFacade;

    @Scheduled(cron = "${point.expiration.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void expire() {
        int expired = pointExpirationFacade.expireAll(ZonedDateTime.now());
        log.info("포인트 만료 처리: {}개 그룹", expired);
    }
}
