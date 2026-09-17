package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "products")
public class ProductModel extends BaseEntity {

    public static final int NAME_MAX_LENGTH = 100;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Column(name = "price", nullable = false)
    private Long price;

    @Column(name = "stock", nullable = false)
    private int stock;

    protected ProductModel() {
    }

    public ProductModel(Long brandId, String name, Long price, int stock) {
        if (brandId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드는 비어있을 수 없습니다.");
        }
        if (stock < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고는 0 이상이어야 합니다.");
        }
        this.brandId = brandId;
        this.name = validateName(name);
        this.price = validatePrice(price);
        this.stock = stock;
    }

    public Long getBrandId() {
        return brandId;
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

    public void update(String name, Long price) {
        this.name = validateName(name);
        this.price = validatePrice(price);
    }

    public void changeStock(int quantity) {
        if (quantity < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고는 0 이상이어야 합니다. (요청: " + quantity + ")");
        }
        this.stock = quantity;
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

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return name;
    }

    private Long validatePrice(Long price) {
        if (price == null || price <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "가격은 양의 정수여야 합니다.");
        }
        return price;
    }
}
