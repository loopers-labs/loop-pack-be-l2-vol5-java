package com.loopers.point.domain;

import com.loopers.common.domain.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 포인트 지갑 판단 (도메인 서비스, ADR-04).
 * 자기 상태 없이 한 사용자의 포인트 그룹들(지갑)을 함께 보고 잔액·충전 가능 여부를 답한다.
 * W5: 주문 결제가 PG로 바뀌어 포인트 결제 배분(pay·checkPayable)은 없앴다 (plan.md W5 §1).
 */
@Component
public class PointWalletPolicy {

    /**
     * PNT-01·PNT-07: 잔액은 지금 쓸 수 있는 그룹의 남은 금액 합이다.
     */
    public Money balanceOf(List<PointGroup> groups, ZonedDateTime now) {
        return groups.stream()
            .filter(group -> group.isUsableAt(now))
            .map(PointGroup::getRemaining)
            .reduce(Money.ZERO, Money::plus);
    }

    /**
     * PNT-03: 충전 후 잔액이 표현 범위를 넘으면 거절한다. 넘침은 Money가 감지하고, 충전 거절이라는 의미는 여기서 정한다.
     */
    public void checkChargeable(List<PointGroup> groups, Money amount, ZonedDateTime now) {
        try {
            balanceOf(groups, now).plus(amount);
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.CONFLICT, "충전 후 잔액이 보유할 수 있는 범위를 넘습니다.");
        }
    }
}
