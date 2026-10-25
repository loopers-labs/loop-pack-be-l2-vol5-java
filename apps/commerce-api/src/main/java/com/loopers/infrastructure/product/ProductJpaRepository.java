package com.loopers.infrastructure.product;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductJpaEntity p WHERE p.id = :productId")
    Optional<ProductJpaEntity> findByIdWithExclusiveLock(@Param("productId") Long productId);

    List<ProductJpaEntity> findAllByDeletedAtIsNull();

    List<ProductJpaEntity> findAllByDeletedAtIsNull(Pageable pageable);

    List<ProductJpaEntity> findAllByDeletedAtIsNullAndBrand_Id(Long brandId, Pageable pageable);

    long countByIdInAndDeletedAtIsNull(List<Long> ids);

    @Query(value = """
        SELECT p.*
        FROM products p
        WHERE p.deleted_at IS NULL
        ORDER BY (
            SELECT COUNT(*)
            FROM likes l
            WHERE l.product_id = p.id
        ) DESC, p.id DESC
        """, nativeQuery = true)
    List<ProductJpaEntity> findAllActiveOrderByLikeCountDesc(Pageable pageable);

    @Query(value = """
        SELECT p.*
        FROM products p
        WHERE p.deleted_at IS NULL
          AND p.brand_id = :brandId
        ORDER BY (
            SELECT COUNT(*)
            FROM likes l
            WHERE l.product_id = p.id
        ) DESC, p.id DESC
        """, nativeQuery = true)
    List<ProductJpaEntity> findAllActiveByBrandIdOrderByLikeCountDesc(@Param("brandId") Long brandId, Pageable pageable);

    List<ProductJpaEntity> findAllByDeletedAtIsNotNull();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE products
        SET name = :name,
            price = :price,
            updated_at = :updatedAt
        WHERE id = :productId
          AND deleted_at IS NULL
        """, nativeQuery = true)
    int updateActiveDetails(
        @Param("productId") Long productId,
        @Param("name") String name,
        @Param("price") long price,
        @Param("updatedAt") ZonedDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE products
        SET stock = :stock,
            updated_at = :updatedAt
        WHERE id = :productId
          AND deleted_at IS NULL
        """, nativeQuery = true)
    int updateActiveStock(
        @Param("productId") Long productId,
        @Param("stock") long stock,
        @Param("updatedAt") ZonedDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE ProductJpaEntity p
        SET p.stock = p.stock - :quantity,
            p.updatedAt = :updatedAt
        WHERE p.id = :productId
          AND p.deletedAt IS NULL
          AND p.stock >= :quantity
        """)
    int decreaseActiveStock(
        @Param("productId") Long productId,
        @Param("quantity") int quantity,
        @Param("updatedAt") ZonedDateTime updatedAt
    );

    // 결제 실패의 재고 반환은 논리 삭제된 상품도 대상이며, 읽은 값이 아니라 현재 재고에 더한다.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE ProductJpaEntity p
        SET p.stock = p.stock + :quantity,
            p.updatedAt = :updatedAt
        WHERE p.id = :productId
        """)
    int increaseStock(
        @Param("productId") Long productId,
        @Param("quantity") int quantity,
        @Param("updatedAt") ZonedDateTime updatedAt
    );

    // READ_COMMITTED의 잠금 없는 최신 조회. 주문 행을 잠그지 않아 TX B의 주문→상품 순서와 교착하지 않는다.
    @Query(value = """
        SELECT COALESCE(SUM(i.quantity), 0)
        FROM order_items i
        JOIN orders o ON o.id = i.order_id
        WHERE i.product_id = :productId AND o.status = 'PAYMENT_PENDING' AND o.payment_method = 'PG'
        """, nativeQuery = true)
    BigDecimal sumPendingReservationQuantity(@Param("productId") Long productId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE products
        SET deleted_at = :deletedAt,
            updated_at = :deletedAt
        WHERE id = :productId
          AND deleted_at IS NULL
        """, nativeQuery = true)
    int softDeleteActiveById(
        @Param("productId") Long productId,
        @Param("deletedAt") ZonedDateTime deletedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE products
        SET deleted_at = :deletedAt,
            updated_at = :deletedAt
        WHERE brand_id = :brandId
          AND deleted_at IS NULL
        ORDER BY id
        """, nativeQuery = true)
    int softDeleteActiveByBrandId(
        @Param("brandId") Long brandId,
        @Param("deletedAt") ZonedDateTime deletedAt
    );
}
