package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    @Query("select p.brandId from ProductModel p where p.id=:id")
    Optional<Long> findBrandId(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductModel p where p.id = :id")
    Optional<ProductModel> findForUpdate(@Param("id") Long id);

    @Query("select p.id from ProductModel p where p.brandId = :brandId and p.deletedAt is null order by p.id")
    List<Long> findActiveIdsByBrandId(@Param("brandId") Long brandId);

    List<ProductModel> findByDeletedAtIsNull();

    @Query("select p from ProductModel p where p.deletedAt is null "
        + "and (:brandId is null or p.brandId = :brandId) "
        + "and exists (select b.id from BrandModel b where b.id = p.brandId and b.deletedAt is null) "
        + "order by p.price asc, p.createdAt desc, p.id desc")
    List<ProductModel> findVisibleByPriceAsc(@Param("brandId") Long brandId, Pageable pageable);

    List<ProductModel> findByBrandIdAndDeletedAtIsNull(Long brandId);

    List<ProductModel> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    @Query("select p.id from ProductModel p where p.brandId = :brandId and p.id in :ids and p.deletedAt is null")
    List<Long> findActiveIds(@Param("brandId") Long brandId, @Param("ids") Collection<Long> ids);
}
