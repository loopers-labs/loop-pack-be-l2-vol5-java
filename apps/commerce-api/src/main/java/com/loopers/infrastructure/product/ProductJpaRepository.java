package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    Optional<ProductModel> findByIdAndDeletedAtIsNull(Long id);

    List<ProductModel> findAllByIdInAndDeletedAtIsNull(Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductModel p where p.id = :id and p.deletedAt is null")
    Optional<ProductModel> findActiveForUpdateById(@Param("id") Long id);

    /** 일반 단일 잠금 쿼리(설계 4.3.3 1단계): 활성 조건, IN, ORDER BY id ASC, 잠금 절. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductModel p where p.id in :ids and p.deletedAt is null order by p.id asc")
    List<ProductModel> findAllActiveForUpdateByIdIn(@Param("ids") Collection<Long> ids);

    /**
     * {@code @Modifying} 의 flushAutomatically·clearAutomatically 는 켜지 않는다.
     * 무조건 clear 하면 아직 flush 하지 않은 변경을 잃을 수 있어 영속성 컨텍스트 처리는 호출 흐름에서 판단한다.
     */
    @Modifying
    @Query(value = """
        UPDATE product
           SET deleted_at = :deletedAt,
               updated_at = :deletedAt
         WHERE brand_id = :brandId
           AND deleted_at IS NULL
        """, nativeQuery = true)
    int softDeleteAllActiveByBrandId(@Param("brandId") Long brandId, @Param("deletedAt") ZonedDateTime deletedAt);
}
