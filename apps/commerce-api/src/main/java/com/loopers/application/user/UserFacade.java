package com.loopers.application.user;

import com.loopers.domain.point.PointService;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class UserFacade {
    private final UserService userService;
    private final PointService pointService;

    /** 테스트용 사용자 생성 (DR-59). 사용자와 잔액 0 인 포인트 계정을 한 TX 에서 만든다 (DR-12). admin 이 없으면 일반 사용자. */
    @Transactional
    public UserInfo create(Boolean admin) {
        UserModel user = userService.create(Boolean.TRUE.equals(admin));
        pointService.openAccount(user.getId());
        return UserInfo.from(user);
    }
}
