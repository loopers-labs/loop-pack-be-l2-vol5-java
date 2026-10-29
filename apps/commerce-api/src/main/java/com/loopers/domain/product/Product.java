package com.loopers.domain.product;

import com.loopers.domain.brand.BrandId;
import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.RuleViolationException;
import java.util.Objects;

public final class Product {
    private final ProductId id;
    private final BrandId brandId;
    private String name;
    private Money price;
    private Stock stock;
    private boolean deleted;

    private Product(ProductId id, BrandId brandId, String name, Money price, Stock stock, boolean deleted) {
        validateName(name);
        Objects.requireNonNull(brandId);
        Objects.requireNonNull(price);
        Objects.requireNonNull(stock);
        this.id = id;
        this.brandId = brandId;
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.deleted = deleted;
    }

    public static Product create(BrandId brandId, String name, Money price, Stock stock) {
        return new Product(null, brandId, name, price, stock, false);
    }

    public static Product restore(ProductId id, BrandId brandId, String name, Money price, Stock stock, boolean deleted) {
        Objects.requireNonNull(id);
        return new Product(id, brandId, name, price, stock, deleted);
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new InvalidValueException("상품 이름은 공백이 아닌 1~100자여야 합니다.");
        }
    }

    private void requireActive() {
        if (deleted) {
            throw new RuleViolationException("삭제된 상품은 변경할 수 없습니다.");
        }
    }

    public void change(String name, Money price) {
        requireActive();
        validateName(name);
        Objects.requireNonNull(price);
        this.name = name;
        this.price = price;
    }

    public void setStock(Stock stock) {
        requireActive();
        Objects.requireNonNull(stock);
        this.stock = stock;
    }

    public void decreaseStock(int quantity) {
        requireActive();
        stock = stock.decrease(quantity);
    }

    // 결제 실패로 확보했던 재고를 돌려준다. 결제 대기 중 삭제된 상품도 주문을 마무리해야 하므로 삭제 여부를 보지 않는다.
    // 삭제 상태는 그대로라 반환된 재고로 다시 노출되지 않는다.
    public void returnStock(int quantity) {
        stock = stock.increase(quantity);
    }

    public void delete() {
        deleted = true;
    }

    public ProductId getId() {
        return id;
    }

    public BrandId getBrandId() {
        return brandId;
    }

    public String getName() {
        return name;
    }

    public Money getPrice() {
        return price;
    }

    public Stock getStock() {
        return stock;
    }

    public boolean isDeleted() {
        return deleted;
    }
}
