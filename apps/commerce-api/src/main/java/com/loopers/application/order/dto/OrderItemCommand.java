package com.loopers.application.order.dto;

/**
 * 주문 생성 요청의 품목 한 줄. interfaces 가 도메인 타입을 알지 않도록 application 이 정한 입력 형식이며,
 * 값 규칙(1 이상의 수량, 중복 금지)은 도메인({@code OrderLine}, {@code OrderLines})이 판단한다.
 */
public record OrderItemCommand(Long productId, int quantity) {}
