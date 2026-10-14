package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Embeddable;
import lombok.Getter;

@Getter
@Embeddable
public class Stock {

    private int quantity;

    protected Stock() {}

    public Stock(int quantity) {
        changeQuantity(quantity);
    }

    /**
     * 증감이 아니라 최종 수량으로 설정한다.
     */
    public void changeQuantity(int quantity) {
        validateQuantity(quantity);
        this.quantity = quantity;
    }

    /**
     * 최종 수량 입력 검증. 실제 설정은 ProductRepository.updateStock 의 단일 UPDATE 가 맡는다.
     */
    public static void validateQuantity(int quantity) {
        if (quantity < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고 수량은 0 이상이어야 합니다.");
        }
    }

    /**
     * 차감 수량 입력 검증. 재고 부족 판정과 차감은 ProductRepository.deductStockIfEnough 의 조건부 UPDATE 가 맡는다.
     */
    public static void validateDeductQuantity(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 수량은 1 이상이어야 합니다.");
        }
    }
}
