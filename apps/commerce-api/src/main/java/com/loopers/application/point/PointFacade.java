package com.loopers.application.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PointFacade {

    private final UserRepository userRepository;
    private final PointRepository pointRepository;

    /**
     * @return 충전 후 잔액
     */
    @Transactional
    public long charge(Long userId, long amount) {
        requireIdentifiedUser(userId);

        Point point = pointRepository.findByUserId(userId)
            .orElseGet(() -> new Point(userId));
        point.charge(amount);

        return pointRepository.save(point).getBalance();
    }

    /**
     * 충전한 적 없는 사용자는 0원으로 본다.
     */
    @Transactional(readOnly = true)
    public long getBalance(Long userId) {
        requireIdentifiedUser(userId);

        return pointRepository.findByUserId(userId)
            .map(Point::getBalance)
            .orElse(0L);
    }

    private void requireIdentifiedUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "사용자 식별에 실패했습니다.");
        }
    }
}
