package com.loopers.domain.pay.repository;

import com.loopers.domain.pay.model.PointBill;

// 포인트 기록 저장소
public interface PointBillRepository {
    PointBill save(PointBill pointBill);
}
