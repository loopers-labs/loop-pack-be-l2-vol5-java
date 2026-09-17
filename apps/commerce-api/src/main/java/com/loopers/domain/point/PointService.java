package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Point 만 주된 상태로 변경하는 유스케이스와 그 트랜잭션 경계를 담당한다.
 * PointHistory 는 이 변경에 부속된 감사 기록으로 함께 저장한다.
 */
@RequiredArgsConstructor
@Component
public class PointService {

    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;

    @Transactional
    public PointChange charge(Long userId, long amount) {
        PointModel point = findPoint(userId);

        PointChange change = point.charge(amount);

        pointHistoryRepository.save(PointHistoryModel.charged(point.getId(), change));
        pointRepository.save(point);
        return change;
    }

    @Transactional(readOnly = true)
    public PointModel getPoint(Long userId) {
        return findPoint(userId);
    }

    private PointModel findPoint(Long userId) {
        return pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.POINT_NOT_INITIALIZED));
    }
}
