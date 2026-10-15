package com.loopers.infrastructure.product;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductJpaRepository extends JpaRepository<ProductJpaEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id = :id")
    Optional<ProductJpaEntity> findForUpdate(@Param("id") long id);

    @Query("select p.id from ProductJpaEntity p where p.brandId = :brandId and p.deleted = false")
    List<Long> findActiveIdsByBrandId(@Param("brandId") long brandId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductJpaEntity p where p.id in :ids order by p.id")
    List<ProductJpaEntity> findAllByIdInForUpdate(@Param("ids") Collection<Long> ids);
}
