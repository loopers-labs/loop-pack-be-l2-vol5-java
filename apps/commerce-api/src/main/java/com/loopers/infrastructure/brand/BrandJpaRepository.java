package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<Brand, Long> {
    Optional<Brand> findByIdAndDeletedAtIsNull(Long id);

    Page<Brand> findAllByDeletedAtIsNullOrderByCreatedAtDescIdDesc(Pageable pageable);

    @Query("select case when count(p) > 0 then true else false end "
        + "from Product p where p.brand.id = :brandId and p.deletedAt is null")
    boolean existsActiveProductByBrandId(@Param("brandId") Long brandId);
}
