package com.loopers.application.point.query;

import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 포인트 조회 유스케이스 (DR-31). */
@RequiredArgsConstructor
@Component
public class PointReader {
    private final UserService userService;
    private final PointQueryRepository pointQueryRepository;

    /**
     * FR-POINT-02 내 잔액 조회. 포인트 계정은 fixture 가 사용자와 함께 준비한다 (DR-12).
     * 사용자는 있는데 계정이 없는 것은 데이터 정합성 문제라 매핑되지 않은 예외(500)로 둔다 (4-4 메모).
     */
    @Transactional(readOnly = true)
    public PointView.Balance getBalance(Long requesterId) {
        userService.getUser(requesterId);
        return pointQueryRepository.findBalanceByUserId(requesterId)
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "[userId = " + requesterId + "] 포인트 계정이 없습니다."));
    }
}
