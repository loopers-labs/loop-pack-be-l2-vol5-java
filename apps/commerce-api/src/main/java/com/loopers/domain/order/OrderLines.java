package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 주문 요청의 품목 목록. 하나 이상이어야 하고, 같은 상품은 한 번만 나올 수 있다.
 *
 * <p>이 규칙은 상품을 조회하기 전에 판단할 수 있어서, 잘못된 요청은 DB 를 조회하지 않고 거절된다.
 * {@link Order#create} 가 이 타입만 받으므로 검증을 건너뛰고 주문을 만들 수 없다.
 * 품목 수량이 곧 상품별 총수량이라 재고 확인에 별도 합산이 필요 없다.
 */
public record OrderLines(List<OrderLine> values) {

    public OrderLines {
        if (values == null || values.isEmpty()) {
            throw new CoreException(ErrorType.INVALID_INPUT, "주문할 상품을 하나 이상 담아 주세요.");
        }
        if (values.stream().anyMatch(line -> line == null)) {
            throw new CoreException(ErrorType.INVALID_INPUT, "주문 품목은 비어 있을 수 없습니다.");
        }
        Set<Long> seen = new HashSet<>();
        for (OrderLine line : values) {
            if (!seen.add(line.productId())) {
                throw new CoreException(
                    ErrorType.INVALID_INPUT,
                    "같은 상품이 중복되었습니다. 수량을 합쳐 다시 요청해 주세요. (상품 ID: " + line.productId() + ")"
                );
            }
        }
        values = List.copyOf(values);
    }

    /** 요청 순서대로의 상품 id. */
    public List<Long> productIds() {
        return values.stream().map(OrderLine::productId).toList();
    }
}
