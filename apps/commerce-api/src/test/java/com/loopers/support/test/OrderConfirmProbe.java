package com.loopers.support.test;

import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.pay.model.PointBillType;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderItemJpaEntity;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderJpaEntity;
import com.loopers.infrastructure.persistence.ordering.entity.QOrderRecordJpaEntity;
import com.loopers.infrastructure.persistence.pay.entity.QPointBillJpaEntity;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// 주문 확정 결과(주문 상태·포인트 기록·주문 기록)를 QueryDSL로 검증 조회하는 테스트 공용 헬퍼
@Component
public class OrderConfirmProbe {
    private static final QOrderJpaEntity ORDER = QOrderJpaEntity.orderJpaEntity;
    private static final QOrderItemJpaEntity ITEM = QOrderItemJpaEntity.orderItemJpaEntity;
    private static final QOrderRecordJpaEntity RECORD = QOrderRecordJpaEntity.orderRecordJpaEntity;
    private static final QPointBillJpaEntity BILL = QPointBillJpaEntity.pointBillJpaEntity;

    @Autowired
    private JPAQueryFactory queryFactory;

    public long countUsePointBills(long userId, long orderId) {
        return queryFactory.select(BILL.count()).from(BILL)
            .where(BILL.userId.eq(userId), BILL.type.eq(PointBillType.USE), BILL.orderId.eq(orderId))
            .fetchOne();
    }

    public long countPaidOrderRecords(long orderId) {
        return queryFactory.select(RECORD.count()).from(RECORD)
            .where(RECORD.order.id.eq(orderId), RECORD.status.eq(OrderRecordStatus.PAID))
            .fetchOne();
    }

    public String orderStatus(long orderId) {
        return queryFactory.select(ORDER.status).from(ORDER).where(ORDER.id.eq(orderId)).fetchOne().name();
    }

    public long orderTotalAmount(long orderId) {
        return queryFactory.select(ORDER.totalAmount).from(ORDER).where(ORDER.id.eq(orderId)).fetchOne();
    }

    public long sumOrderItemQuantity(long orderId) {
        return queryFactory.select(ITEM.quantity).from(ITEM)
            .where(ITEM.order.id.eq(orderId)).fetch().stream().mapToLong(Integer::longValue).sum();
    }

    public long sumPointBillAmountByOrder(long orderId) {
        return queryFactory.select(BILL.amount).from(BILL).where(BILL.orderId.eq(orderId)).fetch()
            .stream().mapToLong(Long::longValue).sum();
    }

    public long sumOrderRecordAmount(long orderId) {
        return queryFactory.select(RECORD.amount).from(RECORD).where(RECORD.order.id.eq(orderId)).fetch()
            .stream().mapToLong(Long::longValue).sum();
    }

    public List<Long> chargeAmounts(long userId) {
        return queryFactory.select(BILL.amount).from(BILL)
            .where(BILL.userId.eq(userId), BILL.type.eq(PointBillType.CHARGE)).fetch();
    }
}
