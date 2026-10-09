package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.BrandModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<BrandModel, Long> {
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from BrandModel b where b.id = :id")
    Optional<BrandModel> findForShare(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BrandModel b where b.id = :id")
    Optional<BrandModel> findForUpdate(@Param("id") Long id);

    boolean existsByNameIgnoreCaseAndDeletedAtIsNull(String name);

    List<BrandModel> findByIdInAndDeletedAtIsNull(Collection<Long> ids);

    List<BrandModel> findByDeletedAtIsNull();
}
