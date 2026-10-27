package com.loopers.domain.point;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PointService {

    private final PointRepository pointRepository;

    /**
     * balance = balance + amount 로 누적한다. 포인트 행이 없으면 새로 만들어 저장한다(첫 충전).
     * 동시 첫 충전은 범위 밖이다.
     *
     * @return 충전 후 잔액
     */
    @Transactional
    public long charge(Long userId, long amount) {
        Point.validateChargeAmount(amount);

        int updated = pointRepository.addBalance(userId, amount);
        Optional<Point> point = pointRepository.findByUserId(userId);
        if (updated > 0) {
            return point.orElseThrow().getBalance();
        }
        if (point.isPresent()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 결과가 표현 가능한 범위를 넘습니다.");
        }

        Point created = new Point(userId);
        created.charge(amount);
        return pointRepository.save(created).getBalance();
    }
}
