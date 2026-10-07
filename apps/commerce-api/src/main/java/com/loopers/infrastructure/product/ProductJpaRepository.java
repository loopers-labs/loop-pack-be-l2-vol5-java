package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    List<ProductModel> findAllByDeletedAtIsNull();

    /**
     * 비관적 락으로 조회한다 — 주문 확정 시 재고 차감 전에만 쓴다.
     * (docs/week2/design.md 5번 섹션 "차감 → 비관적 락" 참고)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductModel p WHERE p.id = :id")
    Optional<ProductModel> findForUpdate(@Param("id") Long id);

    // brand_id 인덱스(ProductModel @Table)를 타야 잠금이 이 브랜드의 행으로 한정된다 — 인덱스가 없으면
    // InnoDB가 훑은 행 전체(=테이블 전체)를 잠근다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductModel p WHERE p.brandId = :brandId AND p.deletedAt IS NULL ORDER BY p.id ASC")
    List<ProductModel> findAllActiveByBrandIdForUpdate(@Param("brandId") Long brandId);

    @Query("SELECT p FROM ProductModel p WHERE p.deletedAt IS NULL "
        + "AND (:brandId IS NULL OR p.brandId = :brandId) "
        + "ORDER BY p.createdAt DESC, p.name ASC, p.id ASC")
    Page<ProductModel> findActiveOrderByLatest(@Param("brandId") Long brandId, Pageable pageable);

    @Query("SELECT p FROM ProductModel p WHERE p.deletedAt IS NULL "
        + "AND (:brandId IS NULL OR p.brandId = :brandId) "
        + "ORDER BY p.price ASC, p.name ASC, p.id ASC")
    Page<ProductModel> findActiveOrderByPriceAsc(@Param("brandId") Long brandId, Pageable pageable);

    // 좋아요 수로 정렬·페이지네이션하려면 DB가 Product·Like를 한 번에 묶어서 세고 잘라야 한다.
    // aggregate 간 JOIN을 영구적인 JPA 연관관계(@ManyToOne 등)로 만들지 않고, 이 조회 쿼리 하나에만
    // 국한해서 예외를 둔다 — ProductModel·LikeModel 도메인 클래스는 서로를 전혀 모른다.
    // (docs/week2/design.md 3번 섹션 "정렬·페이지네이션과 aggregate 경계" 참고)
    @Query(
        value = "SELECT p.* FROM product p LEFT JOIN likes l ON l.product_id = p.id "
            + "WHERE p.deleted_at IS NULL AND (:brandId IS NULL OR p.brand_id = :brandId) "
            + "GROUP BY p.id "
            + "ORDER BY COUNT(l.id) DESC, p.name ASC, p.id ASC",
        countQuery = "SELECT COUNT(*) FROM product p "
            + "WHERE p.deleted_at IS NULL AND (:brandId IS NULL OR p.brand_id = :brandId)",
        nativeQuery = true
    )
    Page<ProductModel> findActiveOrderByLikesDesc(@Param("brandId") Long brandId, Pageable pageable);
}
