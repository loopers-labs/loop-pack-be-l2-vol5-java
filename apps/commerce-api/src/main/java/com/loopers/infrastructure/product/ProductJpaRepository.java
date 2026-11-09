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
    boolean existsByBrandIdAndDeletedAtIsNull(Long brandId);

    Page<ProductModel> findByDeletedAtIsNull(Pageable pageable);

    Page<ProductModel> findByBrandIdAndDeletedAtIsNull(Long brandId, Pageable pageable);

    List<ProductModel> findByIdInAndDeletedAtIsNull(List<Long> ids);

    // 상품마다 좋아요 수를 (product_id, deleted_at) 인덱스로 세어 정렬한다.
    // 좋아요와 조인한 뒤 group by 하는 방식보다 임시 테이블 집계가 없어 실습 DB에서 더 빨랐다
    @Query(
        value = "select p from ProductModel p where p.deletedAt is null "
            + "order by (select count(l) from LikeModel l where l.productId = p.id and l.deletedAt is null) desc, p.id asc",
        countQuery = "select count(p) from ProductModel p where p.deletedAt is null"
    )
    Page<ProductModel> findActiveOrderByLikeCountDesc(Pageable pageable);

    @Query(
        value = "select p from ProductModel p where p.brandId = :brandId and p.deletedAt is null "
            + "order by (select count(l) from LikeModel l where l.productId = p.id and l.deletedAt is null) desc, p.id asc",
        countQuery = "select count(p) from ProductModel p where p.brandId = :brandId and p.deletedAt is null"
    )
    Page<ProductModel> findActiveByBrandIdOrderByLikeCountDesc(@Param("brandId") Long brandId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductModel p where p.id = :id")
    Optional<ProductModel> findByIdForUpdate(@Param("id") Long id);

    @Query("select p.id from ProductModel p where p.brandId = :brandId and p.deletedAt is null order by p.id asc")
    List<Long> findActiveIdsByBrandId(@Param("brandId") Long brandId);
}
