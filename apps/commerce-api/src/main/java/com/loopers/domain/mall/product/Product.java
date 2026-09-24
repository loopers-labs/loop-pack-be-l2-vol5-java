package com.loopers.domain.mall.product;

import com.loopers.domain.shared.Money;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;

// 상품 도메인 모델
public final class Product {
    private final Long id;
    private final long brandId;
    private String name;
    private String description;
    private Money price;
    private Stock stock;
    private boolean deleted;
    private final Instant createdAt;

    private Product(Long id, long brandId, String name, String description, long price, int stock,
                    boolean deleted, Instant createdAt) {
        if (brandId <= 0) {
            throw new IllegalArgumentException("브랜드 ID는 양수여야 합니다.");
        }
        this.id = id;
        this.brandId = brandId;
        this.name = normalizeName(name);
        this.description = validateDescription(description);
        this.price = Money.positive(price);
        this.stock = Stock.of(stock);
        this.deleted = deleted;
        this.createdAt = createdAt;
    }

    public static Product create(long brandId, String name, String description, long price, int stock) {
        return new Product(null, brandId, name, description, price, stock, false, null);
    }

    // 저장된 값으로 상품 복원
    public static Product restore(long id, long brandId, String name, String description, long price, int stock,
                                  boolean deleted, Instant createdAt) {
        if (id <= 0 || createdAt == null) {
            throw new IllegalArgumentException("저장된 상품 상태가 올바르지 않습니다.");
        }
        return new Product(id, brandId, name, description, price, stock, deleted, createdAt);
    }

    // 이름·설명·가격 검증 후 값 갱신
    public void update(String name, String description, long price) {
        ensureActive();
        String normalizedName = normalizeName(name);
        String validatedDescription = validateDescription(description);
        Money validatedPrice = Money.positive(price);
        this.name = normalizedName;
        this.description = validatedDescription;
        this.price = validatedPrice;
    }

    // 재고 수량 설정
    public void setStock(int value) {
        ensureActive();
        stock = stock.set(value);
    }

    // 차감 가능 여부만 검증하고 재고는 바꾸지 않음
    public void ensureCanDecreaseStock(int quantity) {
        ensureActive();
        stock.ensureCanDecrease(quantity);
    }

    // 재고 차감
    public void decreaseStock(int quantity) {
        ensureActive();
        stock = stock.decrease(quantity);
    }

    // 상품 삭제 처리
    public void delete() {
        ensureActive();
        deleted = true;
    }

    // 삭제된 상품인지 검증
    public void ensureActive() {
        if (deleted) {
            throw new DomainException(DomainErrorCode.DELETED_PRODUCT);
        }
    }

    public Long getId() { return id; }
    public long getBrandId() { return brandId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public long getPrice() { return price.getValue(); }
    public int getStock() { return stock.getValue(); }
    public boolean isDeleted() { return deleted; }
    public Instant getCreatedAt() { return createdAt; }

    // 이름 공백 제거 및 길이 검증
    private static String normalizeName(String name) {
        if (name == null) {
            throw new DomainException(DomainErrorCode.INVALID_NAME);
        }
        String normalized = name.trim();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new DomainException(DomainErrorCode.INVALID_NAME);
        }
        return normalized;
    }

    // 설명 길이 검증
    private static String validateDescription(String description) {
        if (description != null && description.length() > 1_000) {
            throw new DomainException(DomainErrorCode.INVALID_DESCRIPTION);
        }
        return description;
    }
}
