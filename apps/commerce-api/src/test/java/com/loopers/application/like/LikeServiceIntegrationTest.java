package com.loopers.application.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class LikeServiceIntegrationTest {

    @Autowired
    private LikeService likeService;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private FixtureUserInitializer initializer;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private Product product;
    private TransactionTemplate transaction;

    @BeforeEach
    void setUp() {
        initializer.initialize();
        Brand brand = brandRepository.save(new Brand("롤백 브랜드"));
        product = productRepository.save(new Product(brand, "롤백 상품", 1000L, 5));
        transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("LIKE-ROLLBACK-01: 좋아요 INSERT 후 트랜잭션 실패는 새 관계만 롤백하고 기존 관계를 보존한다.")
    @Test
    void rollsBackFlushedRegistrationAndPreservesExistingRelations() {
        likeService.register("bob", product.getId());
        var before = jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id");
        IllegalStateException failure = new IllegalStateException("관계 저장 후 실패");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likeService.register("alice", product.getId());
            entityManager.flush();
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(2L);
            throw failure;
        })).isSameAs(failure);

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id")).isEqualTo(before);
    }

    @DisplayName("LIKE-ROLLBACK-02: 좋아요 DELETE 후 트랜잭션 실패는 관계 삭제도 롤백한다.")
    @Test
    void rollsBackPhysicalCancellation() {
        likeService.register("alice", product.getId());
        likeService.register("bob", product.getId());
        var before = jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id");
        IllegalStateException failure = new IllegalStateException("관계 삭제 후 실패");

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            likeService.cancel("alice", product.getId());
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `like`", Long.class)).isEqualTo(1L);
            throw failure;
        })).isSameAs(failure);

        assertThat(jdbcTemplate.queryForList("SELECT * FROM `like` ORDER BY id")).isEqualTo(before);
    }
}
