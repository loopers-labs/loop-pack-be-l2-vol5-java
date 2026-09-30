package com.loopers.point.application;

import com.loopers.point.application.port.in.PointBalanceInfo;
import com.loopers.point.application.port.out.PointGroupPort;
import com.loopers.point.domain.PointGroup;
import com.loopers.point.domain.PointWalletPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 사용 가능한 포인트 잔액 조회.
 */
@RequiredArgsConstructor
@Component
@Transactional(readOnly = true)
public class PointQueryService {

    private final PointGroupPort pointGroupPort;
    private final PointWalletPolicy pointWalletPolicy;

    /**
     * PNT-01·PNT-07: 지금 쓸 수 있는 잔액. 충전한 적이 없으면 0.
     */
    public PointBalanceInfo getBalance(Long userId) {
        List<PointGroup> groups = pointGroupPort.findRemainingByUserId(userId);
        return new PointBalanceInfo(pointWalletPolicy.balanceOf(groups, ZonedDateTime.now()).amount());
    }
}
