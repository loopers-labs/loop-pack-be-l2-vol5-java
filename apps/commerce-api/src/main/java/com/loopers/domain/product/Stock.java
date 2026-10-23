package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Embeddable;

/**
 * 상품의 재고 수량을 나타내는 불변 값 객체.
 *
 * <p>재고는 항상 0 이상이다. 차감은 자신을 바꾸지 않고 새 {@code Stock}을 반환하므로,
 * 거절된 경우에도 기존 재고는 유지된다.
 */
@Embeddable
public record Stock(int remaining) {

    public Stock {
        if (remaining < 0) {
            throw new CoreException(ErrorType.INVALID_INPUT, "재고는 0 이상이어야 합니다.");
        }
    }

    /** 재고가 요청 수량 이상이면 true 이다. 확인만 하고 재고를 바꾸지 않는다. */
    public boolean isEnoughFor(int quantity) {
        return remaining >= quantity;
    }

    /**
     * 재고를 차감한 새 {@code Stock}을 반환한다.
     *
     * <p>입력 자체가 잘못된 경우(0 이하 수량)를 먼저 거절하고, 그다음 보유량 부족을 거절한다.
     * 재고가 0일 때도 0 이하 수량은 재고 부족이 아니라 잘못된 입력으로 구분하기 위해서다.
     */
    public Stock decrease(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.INVALID_INPUT, "차감 수량은 1 이상이어야 합니다.");
        }
        if (quantity > remaining) {
            throw new CoreException(ErrorType.OUT_OF_STOCK);
        }
        return new Stock(remaining - quantity);
    }

    /**
     * 재고를 늘린 새 {@code Stock}을 반환한다. 확정 실패한 결제 대기 주문이 확보했던 수량을 돌려줄 때 쓴다.
     * 같은 주문의 반환이 두 번 일어나지 않게 하는 것은 호출하는 쪽의 몫이다 (주문 상태 전이).
     */
    public Stock increase(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.INVALID_INPUT, "반환 수량은 1 이상이어야 합니다.");
        }
        long sum = (long) remaining + quantity;
        if (sum > Integer.MAX_VALUE) {
            throw new CoreException(ErrorType.INVALID_INPUT, "재고가 허용 범위를 넘습니다.");
        }
        return new Stock((int) sum);
    }
}
