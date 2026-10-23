package com.loopers.application.ordering.result;

import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.PaymentMethod;
import com.loopers.application.pay.query.PaymentAttemptView;

// 주문 확정 결과
public record ConfirmOrderResult(OrderResult order, Long paymentAmount, OrderRecordStatus paymentStatus,
                                  PaymentMethod paymentMethod, PaymentAttemptView paymentAttempt,
                                  boolean completed, boolean attentionRequired) {}
