package com.loopers.application.point;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetPointFacade {
    private final PointBalanceRepository repository;
    private final IdentifyUser identifyUser;

    public long get(Long userId) {
        long id = identifyUser.require(userId);
        return repository.findByUserId(id).map(PointBalance::getBalance).orElse(0L);
    }
}
