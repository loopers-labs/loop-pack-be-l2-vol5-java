package com.loopers.brand.infrastructure;

import com.loopers.brand.domain.Brand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface BrandJpaRepository extends JpaRepository<Brand, Long> {

    List<Brand> findAllByName(String name);
}
