package com.loopers.application.point;

import com.loopers.application.point.dto.PointInfo;
import com.loopers.domain.common.Money;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 포인트 유스케이스.
 *
 * <p>요청자 id 는 식별 필터({@code CustomerIdentityFilter})를 거치고 컨트롤러의 {@code @Requester} 파라미터가
 * {@code null} 이 아님을 보장한다. 잔액을 바꿀 {@code User} 엔티티가 필요해 조회하지만 존재 확인은 필터가 먼저 한다.
 */
@RequiredArgsConstructor
@Component
public class PointFacade {

    private final UserService userService;

    @Transactional
    public PointInfo charge(Long userId, long amount) {
        // 결제와 같은 사용자 행을 바꾸므로, 처음 읽을 때부터 잠가서 낡은 잔액으로 더하지 않는다.
        User user = userService.getForUpdate(userId);
        user.chargePoint(new Money(amount));
        return PointInfo.from(user);
    }

    @Transactional(readOnly = true)
    public PointInfo getBalance(Long userId) {
        User user = userService.get(userId);
        return PointInfo.from(user);
    }
}
