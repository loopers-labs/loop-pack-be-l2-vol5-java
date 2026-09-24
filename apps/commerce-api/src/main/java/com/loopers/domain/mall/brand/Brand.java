package com.loopers.domain.mall.brand;

import com.loopers.domain.mall.product.Product;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import java.time.Instant;
import java.util.List;

// 브랜드 도메인 모델
public final class Brand {
    private final Long id;
    private String name;
    private String description;
    private boolean deleted;
    private final Instant createdAt;
    private final List<Product> products;

    private Brand(Long id, String name, String description, boolean deleted, Instant createdAt,
                  List<Product> products) {
        this.id = id;
        this.name = normalizeName(name);
        this.description = validateDescription(description);
        this.deleted = deleted;
        this.createdAt = createdAt;
        this.products = products;
    }

    public static Brand create(String name, String description) {
        return new Brand(null, name, description, false, null, List.of());
    }

    // 저장된 값으로 브랜드 복원
    public static Brand restore(long id, String name, String description, boolean deleted, Instant createdAt) {
        if (id <= 0 || createdAt == null) {
            throw new IllegalArgumentException("저장된 브랜드 상태가 올바르지 않습니다.");
        }
        return new Brand(id, name, description, deleted, createdAt, List.of());
    }

    // 삭제 전용 조회로 소속 상품까지 함께 복원
    public static Brand restoreForDeletion(long id, String name, String description, boolean deleted,
                                           Instant createdAt, List<Product> products) {
        if (id <= 0 || createdAt == null) {
            throw new IllegalArgumentException("저장된 브랜드 상태가 올바르지 않습니다.");
        }
        if (products == null) {
            throw new IllegalArgumentException("소속 상품 목록을 조회한 뒤 전달해야 합니다.");
        }
        return new Brand(id, name, description, deleted, createdAt, products);
    }

    // 이름·설명 검증 후 값 갱신
    public void update(String name, String description) {
        ensureActive();
        String normalizedName = normalizeName(name);
        String validatedDescription = validateDescription(description);
        this.name = normalizedName;
        this.description = validatedDescription;
    }

    // 브랜드와 연결된 미삭제 상품 전체를 함께 삭제 처리
    public void delete() {
        ensureActive();
        deleted = true;
        products.stream().filter(product -> !product.isDeleted()).forEach(Product::delete);
    }

    // 삭제된 브랜드인지 검증
    public void ensureActive() {
        if (deleted) {
            throw new DomainException(DomainErrorCode.DELETED_BRAND);
        }
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Product> getProducts() {
        return products;
    }

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
