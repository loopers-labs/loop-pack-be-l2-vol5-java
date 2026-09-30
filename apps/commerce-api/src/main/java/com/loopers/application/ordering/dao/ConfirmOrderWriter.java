package com.loopers.application.ordering.dao;

import com.loopers.domain.pay.model.PointBill;

// 주문 확정 시 조회/저장을 담당하는 포트
public interface ConfirmOrderWriter {
    ConfirmOrderLoad load(long orderId);

    void save(ConfirmOrderLoad load, PointBill pointBill);
}
