package com.loopers.domain.brand;

import com.loopers.infrastructure.brand.BrandJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class BrandRepositoryTest {

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("삭제되지 않은 브랜드를 id로 조회할 때, ")
    @Nested
    class FindActiveById {
        @DisplayName("저장된 브랜드는, flush/clear 후 재조회해도 동일한 값으로 조회된다.")
        @Test
        void returnsBrand_afterFlushAndClear() {
            // arrange
            BrandModel saved = brandRepository.save(new BrandModel("나이키"));
            Long id = saved.getId();
            entityManager.flush();
            entityManager.clear();

            // act
            Optional<BrandModel> result = brandRepository.findActiveById(id);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("나이키");
        }

        @DisplayName("삭제된 브랜드는, 조회되지 않는다.")
        @Test
        void returnsEmpty_whenBrandIsDeleted() {
            // arrange
            BrandModel saved = brandRepository.save(new BrandModel("나이키"));
            saved.delete();
            brandJpaRepository.save(saved);
            entityManager.flush();
            entityManager.clear();

            // act
            Optional<BrandModel> result = brandRepository.findActiveById(saved.getId());

            // assert
            assertThat(result).isEmpty();
        }

        @DisplayName("존재하지 않는 id로 조회하면, 빈 결과를 반환한다.")
        @Test
        void returnsEmpty_whenIdDoesNotExist() {
            // act
            Optional<BrandModel> result = brandRepository.findActiveById(999L);

            // assert
            assertThat(result).isEmpty();
        }
    }
}
