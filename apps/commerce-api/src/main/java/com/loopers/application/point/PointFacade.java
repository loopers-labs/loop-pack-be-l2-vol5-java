package com.loopers.application.point;

import com.loopers.domain.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointFacade {
    private final PointService pointService;

    public PointInfo getPoint(Long userId) {
        Long balance = pointService.getBalance(userId);
        return PointInfo.of(userId, balance);
    }

    public PointInfo charge(Long userId, Long amount) {
        Long balance = pointService.charge(userId, amount);
        return PointInfo.of(userId, balance);
    }
}
