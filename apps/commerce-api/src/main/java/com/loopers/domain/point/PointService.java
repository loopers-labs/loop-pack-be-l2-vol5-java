package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class PointService {

    private final PointRepository pointRepository;

    /**
     * 사용자의 포인트 계정. fixture 가 사용자와 함께 준비한다 (DR-12).
     * 사용자는 있는데 계정이 없는 것은 데이터 정합성 문제라 매핑되지 않은 예외(500)로 둔다 (4-4 메모).
     */
    public PointModel getByUserId(Long userId) {
        return pointRepository.findByUserId(userId)
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "[userId = " + userId + "] 포인트 계정이 없습니다."));
    }

    public PointModel charge(Long userId, Long amount) {
        PointModel point = getByUserId(userId);
        point.charge(amount);
        return point;
    }

    /** FR-POINT-03 환불, FR-ADMIN-POINT-02 운영자 차감, FR-ORDER-02 결제 차감 (5-6). */
    public PointModel deduct(Long userId, Long amount) {
        PointModel point = getByUserId(userId);
        point.deduct(amount);
        return point;
    }
}
