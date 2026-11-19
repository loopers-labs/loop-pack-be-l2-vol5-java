package com.loopers.domain.ranking;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.time.ZonedDateTime;

/**
 * W6 consumer(commerce-streamer)가 저장한 구매 활동. batch 는 읽기만 한다.
 * 컬럼 정의는 streamer 의 ProductPurchaseActivity 와 같게 유지한다(local ddl-auto=update 가 원본을 바꾸지 않도록).
 */
@Getter
@Immutable
@Entity
@Table(
    name = "product_purchase_activity",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_product_purchase_activity_order_product", columnNames = {"order_id", "product_id"}
    )
)
public class PurchaseActivity extends BaseEntity {

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "confirmed_at", nullable = false)
    private ZonedDateTime confirmedAt;

    // JPA 가 사용하는 기본 생성자.
    protected PurchaseActivity() {}
}
