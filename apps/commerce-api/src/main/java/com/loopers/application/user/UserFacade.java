package com.loopers.application.user;

import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class UserFacade {
    private final UserService userService;

    public boolean exists(Long userId) {
        return userService.exists(userId);
    }
}
