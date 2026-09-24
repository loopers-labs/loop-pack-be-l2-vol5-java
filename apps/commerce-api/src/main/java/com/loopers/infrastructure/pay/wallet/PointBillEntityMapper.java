package com.loopers.infrastructure.pay.wallet;

import com.loopers.domain.pay.wallet.PointBill;
import org.springframework.stereotype.Component;

@Component
// 포인트 기록 엔티티-도메인 변환기
public class PointBillEntityMapper {
    // 엔티티를 도메인으로 변환
    public PointBill toDomain(PointBillJpaEntity entity) {
        return PointBill.restore(entity.getId(), entity.getUserId(), entity.getType(), entity.getAmount(),
            entity.getOrderId(), entity.getCreatedAt());
    }

    // 도메인을 신규 엔티티로 변환
    public PointBillJpaEntity toNewEntity(PointBill pointBill) {
        return new PointBillJpaEntity(pointBill.getUserId(), pointBill.getType(), pointBill.getAmount(),
            pointBill.getOrderId());
    }
}
