package com.loopers.domain.point;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PointService {

    private final PointRepository pointRepository;

    /**
     * 행이 없으면 잔액 0원 Point 로 본다. "행 없음"을 0원으로 바꾸는 곳은 여기 하나다 (설계 2.3).
     * 조회만으로는 행을 만들지 않는다.
     */
    @Transactional(readOnly = true)
    public Point getPoint(Long userId) {
        return pointRepository.findByUserId(userId).orElseGet(() -> new Point(userId));
    }

    /** 행은 첫 충전 때 생긴다. 충전이 거절되면 저장하지 않는다. */
    @Transactional
    public Point charge(Long userId, long amount) {
        Point point = getPoint(userId);
        point.charge(amount);
        return pointRepository.save(point);
    }
}
