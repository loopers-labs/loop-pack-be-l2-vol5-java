package com.loopers.infrastructure.catalog;

import com.loopers.domain.catalog.BrandModel;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BrandJpaRepository extends JpaRepository<BrandModel, Long> {
}
