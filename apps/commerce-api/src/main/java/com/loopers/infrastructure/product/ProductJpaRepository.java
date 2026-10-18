package com.loopers.infrastructure.product;

import com.loopers.domain.product.ProductModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProductJpaRepository extends JpaRepository<ProductModel, Long> {
    List<ProductModel> findAllByBrandIdAndDeletedAtIsNull(Long brandId);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select p from ProductModel p where p.id in :ids order by p.id")
    List<ProductModel> findAllByIdInForShare(@Param("ids") Collection<Long> ids);

    /** 옛 stock 컬럼만 쓴다 (DR-38). 영속성 컨텍스트를 비우지 않는다 — 같은 트랜잭션의 재고·원장 변경이 버려지지 않게 한다. */
    @Modifying
    @Query("update ProductModel p set p.stock = :stock where p.id = :id")
    int updateLegacyStock(@Param("id") Long id, @Param("stock") int stock);
}
