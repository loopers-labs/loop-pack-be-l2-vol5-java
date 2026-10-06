package com.loopers.application.point;

import com.loopers.domain.point.Point;
import com.loopers.domain.point.PointBalance;
import com.loopers.domain.point.PointRepository;
import com.loopers.application.user.UserValidator;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

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
        PointBalance.validateChargeAmount(amount);

        int updatedRows = pointRepository.increaseBalanceIfWithinMaximum(
            userId, amount, Long.MAX_VALUE, ZonedDateTime.now()
        );
        if (updatedRows == 0) {
            findPointByUserId(userId);
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 후 잔액이 저장 가능한 범위를 초과했습니다.");
        }

        Point savedPoint = findPointByUserId(userId);
        return PointInfo.from(savedPoint);
    }

    private Point findPointByUserId(Long userId) {
        return pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "사용자의 포인트를 찾을 수 없습니다."));
    }
}
