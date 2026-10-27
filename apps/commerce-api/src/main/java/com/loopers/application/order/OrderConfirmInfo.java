package com.loopers.application.order;

/**
 * confirm·callback·reconcile 응답 재료. settled 가 false 면 202(진행 중), true 면 200(종결)으로 응답한다.
 * lookupUnavailable 은 reconcile 에서 PG 조회 실패·충돌로 "확인 불가"일 때만 true 다 (설계 5-6 결정 ⑳). confirm 은 항상 false.
 * transactionKey 와 카드 번호는 담지 않는다 (설계 4-6).
 */
public record OrderConfirmInfo(OrderInfo order, String attemptStatus, boolean settled, boolean lookupUnavailable) {}