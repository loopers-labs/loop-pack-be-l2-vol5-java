package com.loopers.application.user;

import com.loopers.domain.point.PointBalanceModel;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.domain.user.UserModel;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class UserRegistrationService {
    private final UserRepository users;
    private final PointBalanceRepository points;

    @Transactional
    public UserModel register() {
        UserModel user = users.save(new UserModel());
        points.save(new PointBalanceModel(user.getId()));
        return user;
    }
}
