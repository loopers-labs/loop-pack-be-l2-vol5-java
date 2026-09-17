package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 상품 정보와 현재 재고. 재고 규칙은 소유한 Stock 에 맡기고
 * 외부 객체가 수량을 직접 변경하지 못하게 한다.
 */
@Entity
@Table(name = "product")
@Getter
public class ProductModel extends BaseEntity {

    /** [잠정] 상품명은 앞뒤 공백 제거 후 1~100자, 가격은 1~100,000,000원인 정수. */
    private static final int NAME_MAX_LENGTH = 100;
    private static final long MIN_PRICE = 1L;
    private static final long MAX_PRICE = 100_000_000L;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Embedded
    @AttributeOverride(name = "won", column = @Column(name = "price", nullable = false))
    private Money price;

    @Embedded
    private Stock stock;

    protected ProductModel() {}

    private ProductModel(Long brandId, String name, Long price) {
        this.brandId = brandId;
        this.name = validateName(name);
        this.price = validatePrice(price);
        this.stock = Stock.of(0L);
    }

    public static ProductModel create(Long brandId, String name, Long price) {
        return new ProductModel(brandId, name, price);
    }

    public void update(String newName, Long newPrice) {
        String validatedName = validateName(newName);
        Money validatedPrice = validatePrice(newPrice);

        this.name = validatedName;
        this.price = validatedPrice;
    }

    /** 최종 수량으로 재고를 변경한다. */
    public StockChange changeStock(Long finalQuantity) {
        if (finalQuantity == null) {
            throw new CoreException(ErrorType.INVALID_STOCK_QUANTITY);
        }
        return stock.change(finalQuantity);
    }

    public StockChange decreaseStock(long quantity) {
        return stock.decrease(quantity);
    }

    public long getStockQuantity() {
        return stock.getQuantity();
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    private static String validateName(String name) {
        if (name == null) {
            throw new CoreException(ErrorType.INVALID_PRODUCT_NAME);
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.INVALID_PRODUCT_NAME);
        }
        return trimmed;
    }

    private static Money validatePrice(Long price) {
        if (price == null || price < MIN_PRICE || price > MAX_PRICE) {
            throw new CoreException(ErrorType.INVALID_PRODUCT_PRICE);
        }
        return Money.of(price);
    }
}
