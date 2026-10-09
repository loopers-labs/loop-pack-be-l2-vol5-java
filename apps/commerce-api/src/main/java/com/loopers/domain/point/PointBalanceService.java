package com.loopers.domain.point;

import lombok.RequiredArgsConstructor;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;

@RequiredArgsConstructor
@Component
public class PointBalanceService {

    private final PointBalanceRepository pointBalanceRepository;
    private final Clock clock;

    @Transactional
    public PointBalanceModel getRequired(Long userId) {
        return pointBalanceRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "포인트 계정이 누락되었습니다."));
    }

    @Transactional
    public PointBalanceModel getAvailable(Long userId) {
        PointBalanceModel pointBalance = getRequired(userId);
        pointBalance.expire(now());
        return pointBalanceRepository.save(pointBalance);
    }

    @Transactional
    public PointBalanceModel charge(Long userId, long amount) {
        PointBalanceModel pointBalance = getRequired(userId);
        pointBalance.expire(now());
        pointBalance.charge(amount, now());
        return pointBalanceRepository.save(pointBalance);
    }

    @Transactional
    public PointBalanceModel use(Long userId, long amount) {
        PointBalanceModel pointBalance = getRequired(userId);
        pointBalance.use(amount, now());
        return pointBalanceRepository.save(pointBalance);
    }

    @Transactional
    public PointBalanceModel reward(Long userId, long amount) {
        PointBalanceModel pointBalance = getRequired(userId);
        pointBalance.reward(amount, now());
        return pointBalanceRepository.save(pointBalance);
    }

    private ZonedDateTime now() {
        return ZonedDateTime.now(clock);
    }
}
