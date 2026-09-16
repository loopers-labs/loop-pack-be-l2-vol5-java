package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

public class ProductModel {

    private final String name;
    private final Long price;
    private int stock;

    public ProductModel(String name, Long price, int stock) {
        this.name = name;
        this.price = price;
        this.stock = stock;
    }

    public String getName() {
        return name;
    }

    public Long getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public void deductStock(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감 수량은 양수여야 합니다. (요청: " + quantity + ")");
        }
        if (quantity > stock) {
            throw new CoreException(
                ErrorType.BAD_REQUEST,
                "재고가 부족합니다. (재고: " + stock + ", 요청: " + quantity + ")"
            );
        }
        this.stock -= quantity;
    }
}
