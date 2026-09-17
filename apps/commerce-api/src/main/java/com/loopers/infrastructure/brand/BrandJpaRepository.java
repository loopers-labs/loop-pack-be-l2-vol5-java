package com.loopers.infrastructure.brand;

import com.loopers.domain.brand.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BrandJpaRepository extends JpaRepository<Brand, Long> {

    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long brandId);

    Optional<Brand> findByName(String name);
}
