package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 주문 요청 품목 (값 객체). 같은 상품은 합산하고 수량을 검증한다 (ORD-01, P-01, P-18).
 * 상품 정보를 모르므로 상품 조회보다 먼저 입력 오류(400)를 가린다.
 */
public record OrderLines(Map<Long, Integer> quantities) {

    public record Line(Long productId, int quantity) {}

    public OrderLines {
        quantities = Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
    }

    public static OrderLines of(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목은 1개 이상이어야 합니다.");
        }
        Map<Long, Integer> merged = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.productId() == null) {
                throw new CoreException(ErrorType.BAD_REQUEST, "주문 품목의 상품 ID가 필요합니다.");
            }
            if (line.quantity() <= 0) {
                throw new CoreException(ErrorType.BAD_REQUEST, "주문 수량은 1개 이상이어야 합니다.");
            }
            merged.merge(line.productId(), line.quantity(), OrderLines::addQuantity);
        }
        return new OrderLines(merged);
    }

    public Set<Long> productIds() {
        return quantities.keySet();
    }

    private static int addQuantity(int left, int right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new CoreException(ErrorType.BAD_REQUEST, "같은 상품의 주문 수량 합계가 너무 큽니다.");
        }
    }
}
