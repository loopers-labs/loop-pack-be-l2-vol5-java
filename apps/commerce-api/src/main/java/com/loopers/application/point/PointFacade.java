package com.loopers.application.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class PointFacade {

    private final PointService pointService;

    @Transactional
    public Money charge(Long userId, ChargeAmount amount, Instant requestedAt) {
        return pointService.charge(userId, amount, requestedAt);
    }
}
