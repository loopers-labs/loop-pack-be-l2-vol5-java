package com.loopers.application.ordering.order;

import com.loopers.domain.mall.product.Product;
import com.loopers.domain.ordering.order.Order;
import com.loopers.domain.pay.wallet.Wallet;
import java.util.Map;

// 주문 확정에 필요한 조회 데이터 묶음
public record ConfirmOrderLoad(Order order, Map<Long, Product> productsByProductId, Wallet wallet) {}
