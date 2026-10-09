package com.loopers.brand.infrastructure;

import com.loopers.brand.domain.Brand;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface BrandJpaRepository extends JpaRepository<Brand, Long> {

    List<Brand> findAllByName(String name);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from Brand b where b.id = :id")
    Optional<Brand> findForProductCreate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Brand b where b.id = :id")
    Optional<Brand> findForWrite(@Param("id") Long id);
}
