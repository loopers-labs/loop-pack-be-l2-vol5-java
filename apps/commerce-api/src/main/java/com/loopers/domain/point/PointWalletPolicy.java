package com.loopers.domain.point;

import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 포인트 지갑 판단 (도메인 서비스, ADR-04).
 * 자기 상태 없이 한 사용자의 포인트 그룹들(지갑)을 함께 보고 잔액·충전 가능 여부·결제 배분을 답한다.
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

    /**
     * 포인트로 결제하고, 어느 그룹에서 얼마를 썼는지 돌려준다.
     * PNT-04: 잔액이 결제액보다 적으면 어떤 그룹도 바꾸지 않고 거절한다.
     * PNT-05: 만료 시각이 빠른 그룹부터, 그룹마다 쓸 수 있는 만큼 차감한다.
     * PNT-07: 만료된 그룹은 건너뛴다.
     * P-21: 남은 금액이 0원인 그룹에는 0원 사용 내역을 남기지 않는다.
     */
    public List<PointUsage> pay(List<PointGroup> groups, Money amount, ZonedDateTime now) {
        if (amount.isZero()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "결제 금액은 0보다 커야 합니다.");
        }
        if (amount.isGreaterThan(balanceOf(groups, now))) {
            throw new CoreException(ErrorType.CONFLICT, "포인트 잔액이 부족합니다.");
        }
        List<PointGroup> usableByExpiry = groups.stream()
            .filter(group -> group.isUsableAt(now))
            .sorted(Comparator.comparing(PointGroup::getExpiresAt))
            .toList();
        List<PointUsage> usages = new ArrayList<>();
        Money left = amount;
        for (PointGroup group : usableByExpiry) {
            if (left.isZero()) {
                break;
            }
            Money usage = group.getRemaining().isGreaterThan(left) ? left : group.getRemaining();
            if (usage.isZero()) {
                continue;
            }
            group.use(usage, now);
            usages.add(new PointUsage(group, usage));
            left = left.minus(usage);
        }
        return usages;
    }
}
