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
        if (quantity < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고 수량은 0 이상이어야 합니다.");
        }
        this.quantity = quantity;
    }

    public void deduct(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 수량은 1 이상이어야 합니다.");
        }
        if (this.quantity < quantity) {
            throw new CoreException(ErrorType.CONFLICT, "재고가 부족합니다.");
        }
        this.quantity -= quantity;
    }
}
