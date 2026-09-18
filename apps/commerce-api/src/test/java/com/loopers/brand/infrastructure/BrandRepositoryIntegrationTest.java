package com.loopers.brand.infrastructure;

import com.loopers.brand.domain.Brand;
import com.loopers.brand.domain.BrandRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import com.loopers.testcontainers.MySqlTestContainersConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(MySqlTestContainersConfig.class)
@Transactional
class BrandRepositoryIntegrationTest {

    @Autowired
    private BrandRepository repository;

    @Autowired
    private EntityManager entityManager;

    @DisplayName("[R-ADMIN-14] 브랜드를 삭제해도 기존 참조가 깨지지 않도록 기록이 남는다.")
    @Nested
    class PersistLogicalDeletion {

        @DisplayName("[상태 전이] 삭제한 브랜드를 flush·clear 후 다시 조회하면 삭제 상태로 남아 있다.")
        @Test
        void reloadsLogicallyDeletedBrand() {
            Brand brand = repository.save(new Brand("Nike"));
            brand.delete();
            repository.save(brand);
            entityManager.flush();
            entityManager.clear();

            Brand result = repository.findById(brand.getId()).orElseThrow();

            assertThat(result.isDeleted()).isTrue();
        }
    }
}
