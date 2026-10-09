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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductModel p where p.id = :id")
    Optional<ProductModel> findByIdForUpdate(@Param("id") Long id);

    @Query("select p.id from ProductModel p where p.brandId = :brandId and p.deletedAt is null order by p.id asc")
    List<Long> findActiveIdsByBrandId(@Param("brandId") Long brandId);
}
