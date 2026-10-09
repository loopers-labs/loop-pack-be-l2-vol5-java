package com.loopers.infrastructure.persistence.mall.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "products", indexes = {
    @Index(name = "idx_products_deleted_brand_created", columnList = "deleted, brand_id, created_at DESC, id DESC"),
    @Index(name = "idx_products_deleted_brand_price", columnList = "deleted, brand_id, price ASC, id DESC"),
    @Index(name = "idx_products_deleted_like", columnList = "deleted, like_count DESC, id DESC"),
    @Index(name = "idx_products_deleted_brand_like", columnList = "deleted, brand_id, like_count DESC, id DESC")
})
// 상품 JPA 엔티티
public class ProductJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "brand_id", nullable = false)
    private long brandId;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 1_000)
    private String description;
    @Column(nullable = false)
    private long price;
    @Column(nullable = false)
    private int stock;
    // 좋아요 집계 DAO만 갱신하는 조회 전용 컬럼. JPA 저장 시 INSERT/UPDATE 대상에서 제외한다.
    @Column(name = "like_count", insertable = false, updatable = false,
        columnDefinition = "bigint not null default 0")
    private long likeCount;
    @Column(nullable = false)
    private boolean deleted;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ProductJpaEntity() {}

    ProductJpaEntity(long brandId, String name, String description, long price, int stock, boolean deleted) {
        apply(brandId, name, description, price, stock, deleted);
    }

    // 상품 필드 갱신
    void apply(long brandId, String name, String description, long price, int stock, boolean deleted) {
        this.brandId = brandId;
        this.name = name;
        this.description = description;
        this.price = price;
        this.stock = stock;
        this.deleted = deleted;
    }

    public Long getId() { return id; }
    public long getBrandId() { return brandId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public long getPrice() { return price; }
    public int getStock() { return stock; }
    public boolean isDeleted() { return deleted; }
    public Instant getCreatedAt() { return createdAt; }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
