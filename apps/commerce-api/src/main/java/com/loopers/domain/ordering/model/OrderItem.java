package com.loopers.domain.ordering.model;

import com.loopers.domain.shared.Money;

// 주문 품목 도메인 모델
public final class OrderItem {
    private final long productId;
    private final String productName;
    private final Money unitPrice;
    private final int quantity;
    private final Money amount;

    private OrderItem(long productId, String productName, Money unitPrice, int quantity, Money amount) {
        if (productId <= 0) {
            throw new IllegalArgumentException("상품 ID는 양수여야 합니다.");
        }
        if (productName == null || productName.isBlank()) {
            throw new IllegalArgumentException("주문 품목의 상품명이 올바르지 않습니다.");
        }
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
        this.amount = amount;
    }

    // 새 주문 품목 생성
    public static OrderItem create(long productId, String productName, long unitPrice, int quantity) {
        Money price = Money.positive(unitPrice);
        Money amount = price.multiply(quantity);
        return new OrderItem(productId, productName, price, quantity, amount);
    }

    // 저장된 데이터로부터 품목 복원
    public static OrderItem restore(long productId, String productName, long unitPrice, int quantity, long amount) {
        Money price = Money.positive(unitPrice);
        Money expectedAmount = price.multiply(quantity);
        if (expectedAmount.getValue() != amount) {
            throw new IllegalArgumentException("저장된 주문 품목의 금액이 올바르지 않습니다.");
        }
        return new OrderItem(productId, productName, price, quantity, expectedAmount);
    }

    public long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public long getUnitPrice() {
        return unitPrice.getValue();
    }

    public int getQuantity() {
        return quantity;
    }

    public long getAmount() {
        return amount.getValue();
    }

    Money amountAsMoney() {
        return amount;
    }
}
