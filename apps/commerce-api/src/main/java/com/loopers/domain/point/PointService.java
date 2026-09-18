package com.loopers.domain.point;

import com.loopers.domain.common.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class PointService implements PointUsage {

    private final UserPointRepository userPointRepository;

    public Money charge(Long userId, ChargeAmount amount, Instant requestedAt) {
        UserPoint userPoint = userPointRepository.loadForUpdate(userId);
        userPoint.charge(amount, requestedAt);
        return userPointRepository.save(userPoint).getBalance();
    }

    @Override
    public Money use(Long userId, Money amount, Instant now) {
        UserPoint userPoint = userPointRepository.findForUpdate(userId)
            .orElseGet(() -> UserPoint.open(userId));
        userPoint.use(amount, now);
        return userPointRepository.save(userPoint).getBalance();
    }

    public Money getBalance(Long userId) {
        return userPointRepository.findByUserId(userId)
            .map(UserPoint::getBalance)
            .orElse(Money.ZERO);
    }
}
