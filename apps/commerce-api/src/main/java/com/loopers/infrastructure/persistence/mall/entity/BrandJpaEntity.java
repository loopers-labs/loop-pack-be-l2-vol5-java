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
}
