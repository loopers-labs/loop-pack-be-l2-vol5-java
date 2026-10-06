package com.loopers.infrastructure.brand;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<BrandJpaEntity, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long brandId);

    Optional<BrandJpaEntity> findByIdAndDeletedAtIsNull(Long brandId);

    List<BrandJpaEntity> findAllByDeletedAtIsNull();

    List<BrandJpaEntity> findAllByDeletedAtIsNotNull();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE brands
        SET name = :name,
            updated_at = :updatedAt
        WHERE id = :brandId
          AND deleted_at IS NULL
        """, nativeQuery = true)
    int updateActiveName(
        @Param("brandId") Long brandId,
        @Param("name") String name,
        @Param("updatedAt") ZonedDateTime updatedAt
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
        UPDATE brands
        SET deleted_at = :deletedAt,
            updated_at = :deletedAt
        WHERE id = :brandId
          AND deleted_at IS NULL
        """, nativeQuery = true)
    int softDeleteActiveById(
        @Param("brandId") Long brandId,
        @Param("deletedAt") ZonedDateTime deletedAt
    );
}
