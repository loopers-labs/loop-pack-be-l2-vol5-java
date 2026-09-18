package com.loopers.application.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointRepository;
import com.loopers.application.user.UserValidator;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PointFacade {

    private final PointRepository pointRepository;
    private final UserValidator userValidator;

    @Transactional(readOnly = true)
    public PointInfo getBalance(Long userId) {
        userValidator.validateExists(userId);
        Point point = findPointByUserId(userId);
        return PointInfo.from(point);
    }

    @Transactional
    public PointInfo charge(Long userId, long amount) {
        userValidator.validateExists(userId);
        Point point = findPointByUserId(userId);

        point.charge(amount);
        Point savedPoint = pointRepository.save(point);
        return PointInfo.from(savedPoint);
    }

    private Point findPointByUserId(Long userId) {
        return pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자의 포인트를 찾을 수 없습니다."));
    }
}
