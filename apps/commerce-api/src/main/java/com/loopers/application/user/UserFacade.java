package com.loopers.application.user;

import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class UserFacade {
    private final UserService userService;

    public PointInfo chargePoint(Long userId, long amount) {
        UserModel user = userService.chargePoint(userId, amount);
        return PointInfo.from(user);
    }

    public PointInfo getBalance(Long userId) {
        long balance = userService.getBalance(userId);
        return new PointInfo(userId, balance);
    }
}
