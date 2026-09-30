package com.loopers.point.application;

import com.loopers.common.domain.Money;
import com.loopers.point.application.port.in.PointBalanceInfo;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.point.application.port.out.PointGroupPort;
import com.loopers.point.application.port.out.PointHistoryPort;
import com.loopers.point.domain.PointGroup;
import com.loopers.point.domain.PointHistory;
import com.loopers.point.domain.PointWalletPolicy;
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
public class PointCommandService implements PointCommandUseCase {

    private final PointGroupPort pointGroupPort;
    private final PointHistoryPort pointHistoryPort;
    private final PointWalletPolicy pointWalletPolicy;
    private final PointProperties pointProperties;

    /**
     * PNT-02·PNT-03: 충전 한 번이 그룹 하나와 충전 이력 한 줄을 만들고, 충전 후 사용 가능 잔액을 돌려준다.
     */
    @Transactional
    @Override
    public PointBalanceInfo charge(Long userId, long amount) {
        ZonedDateTime now = ZonedDateTime.now();
        Money chargeAmount = toChargeAmount(amount);
        List<PointGroup> groups = new ArrayList<>(pointGroupPort.findRemainingByUserId(userId));
        pointWalletPolicy.checkChargeable(groups, chargeAmount, now);

        PointGroup group = pointGroupPort.save(PointGroup.charge(userId, chargeAmount, now, pointProperties.chargeValidity()));
        pointHistoryPort.save(PointHistory.charge(group, now));

        groups.add(group);
        return new PointBalanceInfo(pointWalletPolicy.balanceOf(groups, now).amount());
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
