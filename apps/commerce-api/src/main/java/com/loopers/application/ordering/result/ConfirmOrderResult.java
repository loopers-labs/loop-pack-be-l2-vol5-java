package com.loopers.application.ordering.result;

import com.loopers.domain.ordering.model.OrderRecordStatus;

// 주문 확정 결과
public record ConfirmOrderResult(OrderResult order, long paymentAmount, OrderRecordStatus paymentStatus) {}
