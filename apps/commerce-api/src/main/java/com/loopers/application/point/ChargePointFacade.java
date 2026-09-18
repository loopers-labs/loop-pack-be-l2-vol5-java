package com.loopers.application.point;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointBalanceRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Transactional
public class ChargePointFacade {
    private final PointBalanceRepository repository;
    private final IdentifyUser identifyUser;

    public long charge(Long userId, Long amount) {
        long id = identifyUser.require(userId);
        if (amount == null) {
            throw new CoreException(ErrorType.INVALID_REQUEST);
        }
        PointBalance point = repository.findByUserId(id).orElseGet(() -> PointBalance.empty(id));
        point.charge(amount);
        return repository.save(point).getBalance();
    }
}
