package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.MoneyConverter;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Comment;

@Entity
@Table(
    name = "products",
    indexes = @Index(name = "idx_products_brand", columnList = "brand_id")
)
public class ProductModel extends BaseEntity {

    private static final int NAME_MAX_LENGTH = 100;
    private static final long PRICE_MIN = 1;
    private static final long PRICE_MAX = 100_000_000;

    @Column(name = "brand_id", nullable = false)
    @Comment("브랜드 식별자 (ADR-01)")
    private Long brandId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    @Comment("상품 이름, 1–100자 (PRD-01)")
    private String name;

    @Convert(converter = MoneyConverter.class)
    @Column(name = "price", nullable = false)
    @Comment("판매 단가, 1–1억 원 (PRD-01)")
    private Money price;

    @Column(name = "stock", nullable = false)
    @Comment("재고 수량, 0 이상 (PRD-01)")
    private int stock;

    protected ProductModel() {}

    /**
     * PRD-01: 이름 1–100자, 가격 1–1억 원, 초기 재고 0 이상. 브랜드는 식별자만 안다 (ADR-01).
     */
    public ProductModel(Long brandId, String name, long price, int stock) {
        this.brandId = brandId;
        this.name = validName(name);
        this.price = validPrice(price);
        this.stock = validStock(stock);
    }

    /**
     * PRD-03: 이름·가격만 바꾼다. 브랜드는 바꾸지 않는다. 하나라도 유효하지 않으면 아무것도 바꾸지 않는다.
     */
    public void update(String name, long price) {
        String newName = validName(name);
        Money newPrice = validPrice(price);
        this.name = newName;
        this.price = newPrice;
    }

    /**
     * PRD-04: 관리자가 0 이상의 최종 수량으로 설정한다.
     */
    public void changeStock(int stock) {
        this.stock = validStock(stock);
    }

    /**
     * PRD-05: 양수 수량만, 보유량 이하만 차감한다. 거절하면 재고는 바뀌지 않는다.
     */
    public void decreaseStock(int quantity) {
        if (quantity <= 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "차감할 수량은 1개 이상이어야 합니다.");
        }
        if (!hasStock(quantity)) {
            throw new CoreException(ErrorType.CONFLICT, "재고가 부족합니다.");
        }
        this.stock -= quantity;
    }

    /**
     * PRD-05: "이만큼 있나?" — 차감과 같은 규칙으로 답한다.
     */
    public boolean hasStock(int quantity) {
        return quantity <= stock;
    }

    /**
     * PRD-06: 삭제된 상품은 팔 수 없다. 404로 답할지 409로 답할지는 호출한 경로가 정한다.
     */
    public boolean isSellable() {
        return getDeletedAt() == null;
    }

    /**
     * 주문에 복사할 상품명·판매 단가. 주문 생성과 확정 재검증이 같은 값을 쓴다 (AI 리뷰 2-3).
     */
    public ProductSnapshot snapshot() {
        return new ProductSnapshot(getId(), name, price);
    }

    private static String validName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 비어 있을 수 없습니다.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    private static Money validPrice(long price) {
        if (price < PRICE_MIN || price > PRICE_MAX) {
            throw new CoreException(ErrorType.BAD_REQUEST, "상품 가격은 " + PRICE_MIN + "원 이상 " + PRICE_MAX + "원 이하여야 합니다.");
        }
        return Money.of(price);
    }

    private static int validStock(int stock) {
        if (stock < 0) {
            throw new CoreException(ErrorType.BAD_REQUEST, "재고는 0 이상이어야 합니다.");
        }
        return stock;
    }

    public Long getBrandId() {
        return brandId;
    }

    public String getName() {
        return name;
    }

    public Money getPrice() {
        return price;
    }

    public int getStock() {
        return stock;
    }
}
