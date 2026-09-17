package com.loopers.application.point;

import com.loopers.domain.point.PointService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class PointFacade {
    private final UserService userService;
    private final PointService pointService;

    /** FR-POINT-01 포인트 충전. 출력은 충전 후 잔액. */
    @Transactional
    public PointInfo charge(Long requesterId, Long amount) {
        userService.getUser(requesterId);
        return PointInfo.from(pointService.charge(requesterId, amount));
    }

    /** FR-POINT-02 내 잔액 조회. */
    @Transactional(readOnly = true)
    public PointInfo getBalance(Long requesterId) {
        userService.getUser(requesterId);
        return PointInfo.from(pointService.getByUserId(requesterId));
    }

    /** FR-POINT-03 포인트 환불 [추가]. 잔액 감소까지만, 돈의 이동 없음 (ASM-24). */
    @Transactional
    public PointInfo refund(Long requesterId, Long amount) {
        userService.getUser(requesterId);
        return PointInfo.from(pointService.deduct(requesterId, amount));
    }

    /** FR-ADMIN-POINT-01 사용자 포인트 충전 [추가]. 대상 사용자 존재는 UserService 로 확인 (5-6). 사유 없음 (ASM-25). */
    @Transactional
    public PointInfo chargeByAdmin(Long requesterId, Long targetUserId, Long amount) {
        userService.getAdmin(requesterId);
        userService.getUser(requireTargetUserId(targetUserId));
        return PointInfo.from(pointService.charge(targetUserId, amount));
    }

    /** FR-ADMIN-POINT-02 사용자 포인트 차감 [추가]. 잔액을 0 까지만 내릴 수 있다 (INV-01). */
    @Transactional
    public PointInfo deductByAdmin(Long requesterId, Long targetUserId, Long amount) {
        userService.getAdmin(requesterId);
        userService.getUser(requireTargetUserId(targetUserId));
        return PointInfo.from(pointService.deduct(targetUserId, amount));
    }

    /** EP-27/28: 바디 userId 누락은 "없는 사용자"(ER-01)가 아니라 요청 형식 오류 ER-22 (DR-24). */
    private static Long requireTargetUserId(Long targetUserId) {
        if (targetUserId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "대상 사용자 userId 가 누락되었습니다.");
        }
        return targetUserId;
    }
}
