package com.loopers.application.order;

/**
 * OrderConfirmed payload를 계약 JSON 문자열로 바꾸는 포트(docs/week7/decisions.md D-12). Jackson을 쓰는 구현
 * (infrastructure의 OrderConfirmedJson)은 기술 코드라 infrastructure에 두고, 확정 TX(application)는 이 포트만 안다 —
 * application → infrastructure 의존은 ArchUnit이 막는다.
 *
 * <p>확정 TX와 6주차 누락 복구가 같은 포트를 지나 같은 문자열을 만든다(D-5, INV-706). 실패하면 예외를 던진다 —
 * 확정 TX 안에서는 그 예외가 확정까지 함께 rollback시킨다(D-6).
 */
public interface OrderConfirmedSerializer {

    String write(OrderConfirmedPayload payload);
}
