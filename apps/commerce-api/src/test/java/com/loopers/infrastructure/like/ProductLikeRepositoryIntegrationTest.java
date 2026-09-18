package com.loopers.infrastructure.like;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.like.ProductLike;
import com.loopers.domain.like.ProductLikeRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.User;
import com.loopers.domain.user.UserRepository;
import com.loopers.infrastructure.user.FixtureUserInitializer;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductLikeRepositoryIntegrationTest {

    @Autowired
    private ProductLikeRepository likeRepository;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FixtureUserInitializer initializer;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Product product;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        initializer.initialize();
        Brand brand = brandRepository.save(new Brand("브랜드"));
        product = productRepository.save(new Product(brand, "상품", 1000L, 5));
        alice = userRepository.findById(1L).orElseThrow();
        bob = userRepository.findById(2L).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("LIKE-PERSIST-01: 사용자·상품 FK와 생성 시각을 저장하고 상품 관계 수를 집계한다.")
    @Test
    void storesUserProductAndCreationTimeAndCountsRelations() {
        ProductLike first = likeRepository.save(new ProductLike(alice, product));
        likeRepository.save(new ProductLike(bob, product));

        assertThat(first.getId()).isPositive();
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(likeRepository.existsByUserIdAndProductId(alice.getId(), product.getId())).isTrue();
        assertThat(likeRepository.existsByUserIdAndProductId(3L, product.getId())).isFalse();
        assertThat(likeRepository.countByProductId(product.getId())).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM `like` ORDER BY user_id", Long.class))
            .containsExactly(1L, 2L);
    }

    @DisplayName("LIKE-PERSIST-02: 같은 사용자·상품 중복 관계는 DB 유일 제약으로 거절한다.")
    @Test
    void enforcesUniqueUserAndProductPair() {
        likeRepository.save(new ProductLike(alice, product));

        assertThatThrownBy(() -> likeRepository.save(new ProductLike(alice, product)))
            .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(likeRepository.countByProductId(product.getId())).isEqualTo(1L);
    }

    @DisplayName("LIKE-PERSIST-03: 본인 관계만 물리 삭제하고 재취소·재등록을 허용한다.")
    @Test
    void physicallyDeletesOnlySelectedUsersRelation() {
        likeRepository.save(new ProductLike(alice, product));
        likeRepository.save(new ProductLike(bob, product));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status ->
            likeRepository.deleteByUserIdAndProductId(alice.getId(), product.getId()));
        transaction.executeWithoutResult(status ->
            likeRepository.deleteByUserIdAndProductId(alice.getId(), product.getId()));

        assertThat(likeRepository.countByProductId(product.getId())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForList("SELECT user_id FROM `like`", Long.class)).containsExactly(2L);
        likeRepository.save(new ProductLike(alice, product));
        assertThat(likeRepository.countByProductId(product.getId())).isEqualTo(2L);
    }

    @DisplayName("LIKE-PERSIST-04: FK가 가리키는 사용자나 상품이 없으면 관계를 저장하지 않는다.")
    @Test
    void rejectsMissingForeignKeyTargets() {
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO `like` (user_id, product_id, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
            Long.MAX_VALUE, product.getId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
            "INSERT INTO `like` (user_id, product_id, created_at) VALUES (?, ?, UTC_TIMESTAMP(6))",
            alice.getId(), Long.MAX_VALUE)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(likeRepository.countByProductId(product.getId())).isZero();
    }
}
