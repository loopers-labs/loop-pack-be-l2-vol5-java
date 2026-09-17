package com.loopers.application.point;

import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointService;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointFacade {

    private final UserService userService;
    private final PointService pointService;

    public PointInfo charge(Long userId, Long amount) {
        userService.getUser(userId);
        PointModel point = pointService.charge(userId, amount);
        return PointInfo.of(point.getUserId(), point.getBalance());
    }

    public PointInfo getPoint(Long userId) {
        userService.getUser(userId);
        return PointInfo.of(userId, pointService.getBalance(userId));
    }
}
