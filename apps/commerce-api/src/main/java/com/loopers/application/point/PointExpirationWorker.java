package com.loopers.application.point;

import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.point.PointBalanceService;
import com.loopers.domain.point.PointBalanceModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

@Component
@RequiredArgsConstructor
public class PointExpirationWorker {
    private final PointBalanceRepository balances;
    private final PointBalanceService pointService;

    @Transactional
    public void expire(Long userId, ZonedDateTime cutoff) {
        PointBalanceModel balance = pointService.getRequired(userId);
        balance.expire(cutoff);
        balances.save(balance);
    }
}
