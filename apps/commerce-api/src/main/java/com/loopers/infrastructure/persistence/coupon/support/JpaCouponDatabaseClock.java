package com.loopers.infrastructure.persistence.coupon.support;

import com.loopers.application.coupon.port.CouponDatabaseClock;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JpaCouponDatabaseClock implements CouponDatabaseClock {
    private final EntityManager entityManager;

    @Override
    public Instant now() {
        // UTC의 두 DATETIME 차이를 사용해 세션 time_zone과 무관한 epoch 마이크로초를 얻는다.
        Number value = (Number) entityManager.createNativeQuery(
            "SELECT TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6))").getSingleResult();
        long micros = value.longValue();
        return Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000), Math.floorMod(micros, 1_000_000) * 1_000);
    }
}
