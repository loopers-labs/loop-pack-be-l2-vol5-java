package com.loopers.infrastructure.brand;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.brand.BrandResult;
import com.loopers.application.brand.port.BrandRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandId;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class BrandPersistenceIntegrationTest {
    @Autowired
    private BrandRepository repository;
    @Autowired
    private BrandJpaRepository jpaRepository;
    @Autowired
    private BrandApplicationService service;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void cleanUp() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("브랜드 저장 후 flush와 clear를 거쳐 조회하면 DB ID와 이름과 활성 상태가 유지된다")
    void savesAndReloadsBrand() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Brand original = Brand.create("가".repeat(100));
            Brand saved = repository.save(original);
            entityManager.flush();
            entityManager.clear();

            Brand found = repository.findById(saved.getId()).orElseThrow();
            assertThat(saved.getId().value()).isPositive();
            assertThat(original.getId()).isNull();
            assertThat(found.getId()).isEqualTo(saved.getId());
            assertThat(found.getName()).isEqualTo(original.getName());
            assertThat(found.isDeleted()).isFalse();
        });
    }

    @Test
    @DisplayName("저장된 삭제 상태를 복원하며 고객 상세 조회에서는 없는 대상 오류로 거절한다")
    void preservesDeletedState() {
        BrandId id = new TransactionTemplate(transactionManager).execute(status -> {
            Brand saved = repository.save(Brand.create("삭제 브랜드"));
            repository.save(Brand.restore(saved.getId(), saved.getName(), true));
            entityManager.flush();
            entityManager.clear();
            assertThat(repository.findById(saved.getId()).orElseThrow().isDeleted()).isTrue();
            assertThat(jpaRepository.count()).isEqualTo(1);
            return saved.getId();
        });
        assertThatThrownBy(() -> service.getBrand(id)).isInstanceOf(BrandNotFoundException.class);
    }

    @Test
    @DisplayName("application에서 생성한 브랜드가 커밋되어 별도 조회에서 반환된다")
    void connectsApplicationToDatabase() {
        BrandResult created = service.create("브랜드");
        assertThat(created.id().value()).isPositive();
        assertThat(service.getBrand(created.id())).isEqualTo(created);
        assertThat(jpaRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("DB에 없는 브랜드는 저장소에서 빈 결과이고 application에서는 없는 대상 오류다")
    void rejectsMissingBrand() {
        BrandId id = new BrandId(999L);
        assertThat(repository.findById(id)).isEmpty();
        assertThatThrownBy(() -> service.getBrand(id)).isInstanceOf(BrandNotFoundException.class);
    }

    @Test
    @DisplayName("잘못된 이름으로 생성하면 추가 저장 없이 기존 브랜드가 유지된다")
    void rejectsInvalidNameWithoutWriting() {
        BrandResult existing = service.create("기존 브랜드");
        assertThatThrownBy(() -> service.create(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(jpaRepository.count()).isEqualTo(1);
        assertThat(service.getBrand(existing.id())).isEqualTo(existing);
    }
}
