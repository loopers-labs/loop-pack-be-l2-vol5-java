package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandErrorCode;
import com.loopers.support.error.CoreException;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * 고객 조회는 브랜드와 삭제 여부로 거른다. (brand_id, deleted_at) 인덱스로 브랜드의 살아 있는 상품을 찾고,
 * 보조 인덱스에 담긴 식별자로 건수를 인덱스만으로 센다 (4주차 docs/week4/product-query-brand-index.md)
 */
@Entity
@Table(name = "product", indexes = @Index(name = "idx_product_brand_deleted", columnList = "brand_id, deleted_at"))
public class Product extends BaseEntity {

    private static final int MAX_NAME_LENGTH = 50;
    private static final long MAX_PRICE = 100_000_000L;

    // 응답 조합에 쓰지 않도록 getter 를 두지 않는다 (설계 D-36)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "brand_id", nullable = false)
    private Brand brand;

    private String name;
    private long price;
    private int stock;

    protected Product() {}

    // 브랜드 없이 존재할 수 없다는 불변식의 생성 입구를 지킨다 (BRD-02, 설계 2.3)
    public Product(Brand brand, String name, long price) {
        if (brand == null || brand.isDeleted()) {
            throw new CoreException(BrandErrorCode.BRAND_NOT_FOUND);
        }
        validateName(name);
        validatePrice(price);
        this.brand = brand;
        this.name = name;
        this.price = price;
        this.stock = 0;
    }

    public String getName() {
        return name;
    }

    public long getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }

    public boolean isDeleted() {
        return getDeletedAt() != null;
    }

    /** 품절은 재고 0 이다. 응답의 품절 여부와 목록 캐시 무효화가 이 기준 하나를 쓴다 (4주차 docs/week4/cache-design.md 3.3) */
    public static boolean isSoldOut(int stock) {
        return stock == 0;
    }

    public boolean isSoldOut() {
        return isSoldOut(stock);
    }

    /**
     * 목록 캐시의 무효화 범위(브랜드)를 정하는 데 쓴다. Brand 를 돌려주지 않아 응답 조합에는 쓸 수 없다 (설계 D-36 4주차 단서).
     * 지연 로딩 참조라도 식별자만 읽으므로 브랜드 행을 조회하지 않는다
     */
    public Long getBrandId() {
        return brand.getId();
    }

    // 브랜드는 바꾸지 않는다 (PRD-03)
    public void update(String name, long price) {
        validateNotDeleted();
        validateName(name);
        validatePrice(price);
        this.name = name;
        this.price = price;
    }

    public void changeStock(int stock) {
        validateNotDeleted();
        updateStock(stock);
    }

    /**
     * 0 이하이거나 보유량보다 많으면 거절함 (PRD-06). 이유와 관계없이 OUT_OF_STOCK 임.
     * 여러 품목 중 어느 상품인지 알 수 있도록 자신의 식별자를 담음 (설계 4.4, 6.4)
     */
    public void decrease(int quantity) {
        if (quantity <= 0 || quantity > stock) {
            throw new CoreException(ProductErrorCode.OUT_OF_STOCK, null, ProductErrorDetail.of(getId()));
        }
        updateStock(stock - quantity);
    }

    // 재고를 바꾸는 모든 행동이 거치는 한 곳. "재고는 0 이상"(PRD-05)을 여기서만 지킨다.
    private void updateStock(int newStock) {
        if (newStock < 0) {
            throw new CoreException(ProductErrorCode.INVALID_STOCK);
        }
        this.stock = newStock;
    }

    private void validateNotDeleted() {
        if (isDeleted()) {
            throw new CoreException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }
    }

    // 앞뒤 공백을 자르지 않는다. 길이에는 공백이 포함된다 (설계 6.5)
    private static void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ProductErrorCode.INVALID_PRODUCT_NAME);
        }
    }

    private static void validatePrice(long price) {
        if (price < 0 || price > MAX_PRICE) {
            throw new CoreException(ProductErrorCode.INVALID_PRICE);
        }
    }
}
