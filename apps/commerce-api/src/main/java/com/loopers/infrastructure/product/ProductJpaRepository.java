package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {

    Optional<ProductModel> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByBrandIdAndDeletedAtIsNull(Long brandId);

    @Query(value = """
        select p from ProductModel p
        where p.deletedAt is null and (:brandId is null or p.brandId = :brandId)
        order by p.id desc
        """)
    Page<ProductModel> findActiveOrderByLatest(@Param("brandId") Long brandId, Pageable pageable);

    @Query(value = """
        select p from ProductModel p
        where p.deletedAt is null and (:brandId is null or p.brandId = :brandId)
        order by p.price asc, p.id desc
        """)
    Page<ProductModel> findActiveOrderByPriceAsc(@Param("brandId") Long brandId, Pageable pageable);

    @Query(value = """
        select p from ProductModel p
        left join LikeModel l on l.productId = p.id
        where p.deletedAt is null and (:brandId is null or p.brandId = :brandId)
        group by p
        order by count(l.id) desc, p.id desc
        """,
        countQuery = """
        select count(p) from ProductModel p
        where p.deletedAt is null and (:brandId is null or p.brandId = :brandId)
        """)
    Page<ProductModel> findActiveOrderByLikesDesc(@Param("brandId") Long brandId, Pageable pageable);
}
