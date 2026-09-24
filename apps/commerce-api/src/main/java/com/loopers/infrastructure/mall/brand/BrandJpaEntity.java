package com.loopers.infrastructure.mall.brand;

import com.loopers.infrastructure.mall.product.ProductJpaEntity;
import jakarta.persistence.Column;
import jakarta.persistence.ConstraintMode;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "brands", indexes = {
    @Index(name = "idx_brands_deleted_created", columnList = "deleted, created_at DESC, id DESC")
})
// 브랜드 JPA 엔티티
public class BrandJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 1_000)
    private String description;
    @Column(nullable = false)
    private boolean deleted;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    // 조회 전용 단방향 연관관계. FK 저장은 ProductJpaEntity.brandId가 담당하며,
    // 기존처럼 물리적 FK 제약은 생성하지 않는다(NO_CONSTRAINT).
    @OneToMany(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", insertable = false, updatable = false,
        foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private List<ProductJpaEntity> products = new ArrayList<>();

    protected BrandJpaEntity() {}

    BrandJpaEntity(String name, String description, boolean deleted) {
        apply(name, description, deleted);
    }

    // 이름·설명·삭제여부 갱신
    void apply(String name, String description, boolean deleted) {
        this.name = name;
        this.description = description;
        this.deleted = deleted;
    }

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

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isDeleted() { return deleted; }
    public Instant getCreatedAt() { return createdAt; }
    public List<ProductJpaEntity> getProducts() { return products; }
}
