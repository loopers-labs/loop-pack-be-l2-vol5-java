package com.loopers.application.point;

import com.loopers.domain.point.PointChange;
import com.loopers.domain.point.PointHistoryModel;
import com.loopers.domain.point.PointHistoryRepository;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Point 변경과 부속 History 저장을 하나의 API 유스케이스 트랜잭션으로 묶는다. */
@RequiredArgsConstructor
@Component
public class PointFacade {

    private final PointRepository pointRepository;
    private final PointHistoryRepository pointHistoryRepository;

    /** 주문 결제와 같은 Point 행을 바꾸므로 첫 조회부터 잠근 현재 잔액에서 충전한다. */
    @Transactional
    public PointChange charge(Long userId, long amount) {
        PointModel point = pointRepository.findByUserIdForUpdate(userId)
            .orElseThrow(() -> new CoreException(ErrorType.POINT_NOT_INITIALIZED));
        PointChange change = point.charge(amount);

        pointHistoryRepository.save(PointHistoryModel.charged(point.getId(), change));
        pointRepository.save(point);
        return change;
    }

    @Transactional(readOnly = true)
    public PointModel getPoint(Long userId) {
        return pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.POINT_NOT_INITIALIZED));
    }
}
