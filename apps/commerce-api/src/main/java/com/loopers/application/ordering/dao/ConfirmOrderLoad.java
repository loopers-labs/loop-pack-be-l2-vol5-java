package com.loopers.application.ordering.dao;

import com.loopers.domain.mall.model.Product;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.pay.model.Wallet;
import java.util.Map;

// 주문 확정에 필요한 조회 데이터 묶음
public record ConfirmOrderLoad(Order order, Map<Long, Product> productsByProductId, Wallet wallet) {}
