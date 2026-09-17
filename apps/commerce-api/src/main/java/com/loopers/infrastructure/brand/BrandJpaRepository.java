package com.loopers.infrastructure.brand;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandJpaRepository extends JpaRepository<BrandJpaEntity, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from BrandJpaEntity b where b.id = :id")
    java.util.Optional<BrandJpaEntity> findForUpdate(@org.springframework.data.repository.query.Param("id") long id);
}
