package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    List<ProductModel> findAllByBrandIdAndDeletedAtIsNull(Long brandId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductModel p where p.id in :ids order by p.id")
    List<ProductModel> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);
}
