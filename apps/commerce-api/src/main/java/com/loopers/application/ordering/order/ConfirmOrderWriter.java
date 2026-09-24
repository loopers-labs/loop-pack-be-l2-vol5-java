package com.loopers.application.ordering.order;

import com.loopers.domain.pay.orderbill.OrderBill;
import com.loopers.domain.pay.wallet.PointBill;

// 주문 확정 시 조회/저장을 담당하는 포트
public interface ConfirmOrderWriter {
    ConfirmOrderLoad load(long orderId);

    void save(ConfirmOrderLoad load, PointBill pointBill, OrderBill orderBill);
}
