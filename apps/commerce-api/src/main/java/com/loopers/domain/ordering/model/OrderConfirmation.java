package com.loopers.domain.ordering.model;

import com.loopers.domain.mall.model.Product;
import com.loopers.domain.pay.model.PointBill;
import com.loopers.domain.pay.model.Wallet;
import java.util.Map;

// 주문 확정 결과로 변경된 애그리거트와 생성된 결제 영수증 묶음
public record OrderConfirmation(Order order, Map<Long, Product> productsByProductId, Wallet wallet,
                                PointBill pointBill) {}
