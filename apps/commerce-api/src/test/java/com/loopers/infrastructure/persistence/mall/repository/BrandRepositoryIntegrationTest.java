package com.loopers.infrastructure.persistence.mall.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class BrandRepositoryIntegrationTest {
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private EntityManager entityManager;

    @DisplayName("쓰기·공유 잠금 조회로도 브랜드를 읽는다")
    @Test
    @Transactional
    void readsBrand_withWriteAndShareLocks() {
        Brand brand = brandRepository.save(Brand.create("브랜드", "설명"));
        entityManager.flush();
        entityManager.clear();

        assertThat(brandRepository.findByIdForUpdate(brand.getId()).orElseThrow().getName()).isEqualTo("브랜드");
        assertThat(brandRepository.findByIdForShare(brand.getId()).orElseThrow().getDescription()).isEqualTo("설명");
        assertThat(brandRepository.findByIdForUpdate(brand.getId() + 1)).isEmpty();
        assertThat(brandRepository.findByIdForShare(brand.getId() + 1)).isEmpty();
    }
}
