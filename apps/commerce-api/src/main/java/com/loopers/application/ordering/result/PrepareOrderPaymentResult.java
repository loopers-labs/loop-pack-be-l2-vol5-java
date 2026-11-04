package com.loopers.application.ordering.result;

import com.loopers.domain.pay.model.PaymentAttempt;

/** 발송 권한은 준비 commit이 성공한 최초 호출의 반환값이다. 저장된 STARTED에서 다시 만들지 않는다. */
public record PrepareOrderPaymentResult(PaymentAttempt attempt, boolean dispatchAllowed) {}
