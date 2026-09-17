package com.loopers.application.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointGroup;
import com.loopers.domain.point.PointGroupRepository;
import com.loopers.domain.point.PointHistory;
import com.loopers.domain.point.PointHistoryRepository;
import com.loopers.domain.point.PointWalletPolicy;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
@Component
public class PointFacade {

    private final PointGroupRepository pointGroupRepository;
    private final PointHistoryRepository pointHistoryRepository;
    private final PointWalletPolicy pointWalletPolicy;
    private final PointProperties pointProperties;

    /**
     * PNT-02·PNT-03: 충전 한 번이 그룹 하나와 충전 이력 한 줄을 만들고, 충전 후 사용 가능 잔액을 돌려준다.
     */
    @Transactional
    public PointBalanceInfo charge(Long userId, long amount) {
        ZonedDateTime now = ZonedDateTime.now();
        Money chargeAmount = toChargeAmount(amount);
        List<PointGroup> groups = new ArrayList<>(pointGroupRepository.findRemainingByUserId(userId));
        pointWalletPolicy.checkChargeable(groups, chargeAmount, now);

        PointGroup group = pointGroupRepository.save(PointGroup.charge(userId, chargeAmount, now, pointProperties.chargeValidity()));
        pointHistoryRepository.save(PointHistory.charge(group, now));

        groups.add(group);
        return new PointBalanceInfo(pointWalletPolicy.balanceOf(groups, now).amount());
    }

    /**
     * PNT-01·PNT-07: 지금 쓸 수 있는 잔액. 충전한 적이 없으면 0.
     */
    @Transactional(readOnly = true)
    public PointBalanceInfo getBalance(Long userId) {
        List<PointGroup> groups = pointGroupRepository.findRemainingByUserId(userId);
        return new PointBalanceInfo(pointWalletPolicy.balanceOf(groups, ZonedDateTime.now()).amount());
    }

    /**
     * 음수 입력은 Money가 만들 수 없는 값이다. 그 사실을 "충전 입력 오류(400)"로 해석하는 곳이 충전 유스케이스다 (ADR-12).
     * 0원은 금액으로는 유효하므로 그룹 생성 조건(PNT-02)이 거절한다.
     */
    private Money toChargeAmount(long amount) {
        try {
            return Money.of(amount);
        } catch (IllegalArgumentException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 0보다 커야 합니다.");
        }
    }
}
