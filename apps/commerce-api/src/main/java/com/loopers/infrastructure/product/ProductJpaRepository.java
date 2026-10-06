package com.loopers.infrastructure.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {

    List<ProductJpaEntity> findAllByDeletedAtIsNull();

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
