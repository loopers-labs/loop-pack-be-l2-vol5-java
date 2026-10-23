package com.loopers.domain.product;

import com.loopers.domain.BaseEntity;
import com.loopers.domain.common.Money;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * 판매하는 상품. 브랜드는 id 로만 참조한다.
 *
 * <p>이름은 앞뒤 공백을 제거한 뒤 1~100자, 가격은 1원 이상 1,000,000,000원 이하여야 한다. 가격 상한은
 * {@code 단가 × 수량} 합계가 {@code long} 범위를 넘지 않게 하려는 것이다.
 * 검증이 실패하면 필드를 바꾸기 전에 예외가 발생하므로 기존 값이 유지된다.
 */
@Getter
@Entity
// 브랜드별 조회(고객 목록의 브랜드 필터, 브랜드 삭제 시 연결된 상품 확인)에 쓰는 보조 인덱스.
// 고객 목록 price_asc 의 where(brand_id 동등, deleted_at is null)와 order by(price asc, id desc)를 인덱스 순서로 처리하고
// count 를 커버링으로 만든다. 정렬이 price 오름차순에 id 내림차순이라 id 는 DESC 로 둔다(MySQL 8 내림차순 인덱스).
@Table(name = "products", indexes = @Index(
    name = "idx_products_brand_price_id", columnList = "brand_id, deleted_at, price, id DESC"))
public class Product extends BaseEntity {

    private static final int NAME_MAX_LENGTH = 100;
    private static final long PRICE_MIN = 1L;
    private static final long PRICE_MAX = 1_000_000_000L;

    @Column(name = "brand_id", nullable = false)
    private Long brandId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    @Embedded
    @AttributeOverride(name = "amount", column = @Column(name = "price", nullable = false))
    private Money price;

    @Embedded
    @AttributeOverride(name = "remaining", column = @Column(name = "stock", nullable = false))
    private Stock stock;

    // JPA 가 사용하는 기본 생성자. 새 상품은 create() 로 만든다.
    protected Product() {}

    public static Product create(Long brandId, String name, Money price, Stock stock) {
        if (brandId == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "브랜드는 필수입니다.");
        }
        if (stock == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "재고는 필수입니다.");
        }
        Product product = new Product();
        product.brandId = brandId;
        product.name = validatedName(name);
        product.price = validatedPrice(price);
        product.stock = stock;
        return product;
    }

    /** 이름과 가격을 바꾼다. 브랜드와 재고는 바뀌지 않는다. */
    public void update(String name, Money price) {
        String validName = validatedName(name);
        Money validPrice = validatedPrice(price);
        this.name = validName;
        this.price = validPrice;
    }

    /** 재고를 최종 수량으로 바꾼다. 음수이면 {@link Stock} 이 거절한다. */
    public void changeStock(int quantity) {
        this.stock = new Stock(quantity);
    }

    /**
     * 재고를 차감한다. 수량이 0 이하이면 {@code INVALID_INPUT}, 보유량을 넘으면 {@code OUT_OF_STOCK} 이며
     * 거절되면 기존 재고가 유지된다 ({@link Stock} 이 불변이라 새 값을 만들지 못하면 필드를 바꾸지 않는다).
     */
    public void decreaseStock(int quantity) {
        this.stock = stock.decrease(quantity);
    }

    /**
     * 재고를 돌려준다. 수량이 0 이하이거나 합계가 허용 범위를 넘으면 {@code INVALID_INPUT} 이며 기존 재고가 유지된다.
     * 삭제 여부({@code deletedAt})는 건드리지 않는다. 삭제된 상품도 수량만 돌려받는다.
     */
    public void increaseStock(int quantity) {
        this.stock = stock.increase(quantity);
    }

    /** 재고가 주문 수량 이상인지 확인한다. 확인만 하고 재고를 바꾸지 않는다 (차감은 {@link #decreaseStock}). */
    public boolean hasEnoughStock(int quantity) {
        return stock.isEnoughFor(quantity);
    }

    private static String validatedName(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.INVALID_INPUT, "상품 이름은 비어 있을 수 없습니다.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw new CoreException(ErrorType.INVALID_INPUT, "상품 이름은 " + NAME_MAX_LENGTH + "자 이하여야 합니다.");
        }
        return trimmed;
    }

    private static Money validatedPrice(Money price) {
        if (price == null) {
            throw new CoreException(ErrorType.INVALID_INPUT, "가격은 필수입니다.");
        }
        if (price.amount() < PRICE_MIN || price.amount() > PRICE_MAX) {
            throw new CoreException(ErrorType.INVALID_INPUT, "가격은 " + PRICE_MIN + "원 이상 " + PRICE_MAX + "원 이하여야 합니다.");
        }
        return price;
    }
}
