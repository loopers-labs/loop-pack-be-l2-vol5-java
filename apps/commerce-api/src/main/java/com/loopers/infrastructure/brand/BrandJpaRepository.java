package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface BrandJpaRepository extends JpaRepository<Brand, Long> {

    @Transactional(readOnly = true)
    List<Brand> findAllByIdIn(Collection<Long> ids);
}
