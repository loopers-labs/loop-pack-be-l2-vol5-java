package com.loopers.application.point;

import com.loopers.domain.point.PointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointFacade {
    private final PointService pointService;

    public PointInfo charge(Long userId, long amount) {
        return PointInfo.from(pointService.charge(userId, amount));
    }

    public PointInfo getPoint(Long userId) {
        return PointInfo.from(pointService.getPoint(userId));
    }
}
