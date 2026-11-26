package com.loopers.domains.order.app;

// 미확인 목록 한 항목이다. status 값에 따라 결제 대기 PG 결제나 환불 확인 대상 중 한 원천에서만 온다(10주차 5단계 P7).
public sealed interface UnresolvedItem permits UnresolvedPaymentView, UnresolvedRefundCaseView {
}
