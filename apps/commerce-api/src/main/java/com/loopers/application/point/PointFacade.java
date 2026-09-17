package com.loopers.application.point;

import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointFacade {
    private final UserService userService;

    public PointInfo charge(Long userId, Long amount) {
        return new PointInfo(userService.charge(userId, amount));
    }

    public PointInfo getBalance(Long userId) {
        return new PointInfo(userService.getUser(userId).getBalance());
    }
}
