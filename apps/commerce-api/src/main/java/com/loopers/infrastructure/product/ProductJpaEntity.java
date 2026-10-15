package com.loopers.infrastructure.product;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

// 인덱스는 고객 목록 쿼리(정렬 3종 × 브랜드 유무)에 맞춘다. 근거는 설계 문서 17절.
@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_products_brand_price", columnList = "brand_id, price asc, id desc"),
    @Index(name = "idx_products_brand_created", columnList = "brand_id, created_at"),
    @Index(name = "idx_products_created", columnList = "created_at desc, id desc"),
    @Index(name = "idx_products_price", columnList = "price asc, id desc"),
    @Index(name = "idx_products_brand_likes", columnList = "brand_id, like_count desc, id desc"),
    @Index(name = "idx_products_likes", columnList = "like_count desc, id desc")
})
public class ProductJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, updatable = false)
    private long brandId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(nullable = false)
    private long price;
    @Column(nullable = false)
    private int stock;
    @Column(nullable = false)
    private boolean deleted;
    @Column(nullable = false, updatable = false)
    private Instant createdAt;
    // 좋아요 수 요약 복사본. 쓰기 가능하면 상품 저장 때 읽어 둔 값으로 덮어쓸 수 있어 읽기 전용으로 매핑하고,
    // ProductPersistenceAdapter.adjustLikeCount의 원자적 UPDATE로만 바꾼다(설계 18절).
    @ColumnDefault("0")
    @Column(nullable = false, insertable = false, updatable = false)
    private long likeCount;

    protected ProductJpaEntity() { }

    ProductJpaEntity(long brandId, String name, long price, int stock) {
        this.brandId = brandId;
        this.createdAt = Instant.now();
        update(name, price, stock, false);
    }

    void update(String name, long price, int stock, boolean deleted) {
        this.name = name;
        this.price = price;
        this.stock = stock;
        this.deleted = deleted;
    }

    Long getId() { return id; }
    long getBrandId() { return brandId; }
    String getName() { return name; }
    long getPrice() { return price; }
    int getStock() { return stock; }
    boolean isDeleted() { return deleted; }
}
