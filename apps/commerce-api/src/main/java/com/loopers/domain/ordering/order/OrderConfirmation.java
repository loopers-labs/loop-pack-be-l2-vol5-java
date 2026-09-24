package com.loopers.domain.ordering.order;

import com.loopers.domain.mall.product.Product;
import com.loopers.domain.pay.wallet.PointBill;
import com.loopers.domain.pay.wallet.Wallet;
import java.util.Map;

// 주문 확정 결과로 변경된 애그리거트와 생성된 사용 기록 묶음
public record OrderConfirmation(Order order, Map<Long, Product> productsByProductId, Wallet wallet,
                                PointBill pointBill) {}
