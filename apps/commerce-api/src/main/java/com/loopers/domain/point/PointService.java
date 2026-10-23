package com.loopers.domain.point;

import com.loopers.domain.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PointService {

    private final UserPointRepository userPointRepository;

    public Money charge(Long userId, ChargeAmount amount, Instant requestedAt) {
        UserPoint userPoint = userPointRepository.loadForUpdate(userId);
        userPoint.charge(amount, requestedAt);
        return userPointRepository.save(userPoint).getBalance();
    }

    public Money getBalance(Long userId) {
        return userPointRepository.findByUserId(userId)
            .map(UserPoint::getBalance)
            .orElse(Money.ZERO);
    }
}
