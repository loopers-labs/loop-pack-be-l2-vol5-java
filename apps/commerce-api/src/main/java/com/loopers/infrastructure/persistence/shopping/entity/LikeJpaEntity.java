package com.loopers.infrastructure.persistence.shopping.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

@Entity
@Table(name = "product_likes", uniqueConstraints = @UniqueConstraint(
    name = "uk_product_likes_user_product", columnNames = {"product_id", "user_id"}
), indexes = {
    @Index(name = "idx_product_likes_user_created", columnList = "user_id, created_at DESC, product_id DESC")
})
// 좋아요 JPA 엔티티
public class LikeJpaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private long userId;
    @Column(name = "product_id", nullable = false)
    private long productId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LikeJpaEntity() {}
}
