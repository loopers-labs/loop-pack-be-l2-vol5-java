package com.loopers.application.shopping.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.shopping.usecase.LikeCountAggregationUseCase;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.shopping.model.Like;
import com.loopers.domain.shopping.repository.LikeRepository;
import com.loopers.infrastructure.dao.shopping.JdbcLikeCountAggregationDao;
import com.loopers.infrastructure.persistence.mall.entity.QProductJpaEntity;
import com.loopers.support.test.IntegrationTest;
import com.loopers.utils.DatabaseCleanUp;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

@IntegrationTest
class LikeCountAggregationIntegrationTest {
    private static final QProductJpaEntity PRODUCT = QProductJpaEntity.productJpaEntity;

    @Autowired
    private LikeCountAggregationUseCase aggregationUseCase;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private LikeRepository likeRepository;
    @Autowired
    private JPAQueryFactory queryFactory;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;
    @Autowired
    private JdbcLikeCountAggregationDao aggregationDao;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("전체 관계 COUNT를 상품에 저장하고 관계가 사라진 기존 값은 0으로 갱신한다")
    @Test
    void aggregatesAllCounts_andResetsStaleCount() {
        long liked = insertProduct(0L);
        long stale = insertProduct(4L);
        transactionTemplate.executeWithoutResult(status -> {
            likeRepository.save(Like.create(1L, liked));
            likeRepository.save(Like.create(2L, liked));
        });

        aggregationUseCase.execute();

        assertThat(findCount(liked)).isEqualTo(2L);
        assertThat(findCount(stale)).isZero();
    }

    @DisplayName("증감분 반영은 기존 좋아요 수에 더한다")
    @Test
    void addDeltas_addsToExistingCount() {
        long first = insertProduct(5L);
        long second = insertProduct(5L);

        aggregationDao.addDeltas(Map.of(first, 2L, second, -3L));

        assertThat(findCount(first)).isEqualTo(7L);
        assertThat(findCount(second)).isEqualTo(2L);
    }

    @DisplayName("증감분 반영은 결과가 음수가 되면 0으로 맞춘다")
    @Test
    void addDeltas_clampsAtZero() {
        long first = insertProduct(1L);
        long second = insertProduct(0L);

        aggregationDao.addDeltas(Map.of(first, -5L, second, -2L));

        assertThat(findCount(first)).isZero();
        assertThat(findCount(second)).isZero();
    }

    @DisplayName("증감분 반영은 없는 상품 id의 증감분을 무시한다")
    @Test
    void addDeltas_ignoresUnknownProduct() {
        long existing = insertProduct(1L);
        long unknownId = existing + 1_000L;

        aggregationDao.addDeltas(Map.of(existing, 1L, unknownId, 3L));

        assertThat(findCount(existing)).isEqualTo(2L);
        assertThat(queryFactory.select(PRODUCT.count()).from(PRODUCT).fetchOne()).isEqualTo(1L);
    }

    private long insertProduct(long likeCount) {
        long productId = productRepository.save(Product.create(1L, "상품", null, 1_000L, 10)).getId();
        if (likeCount != 0L) {
            transactionTemplate.executeWithoutResult(status -> queryFactory.update(PRODUCT)
                .set(PRODUCT.likeCount, likeCount)
                .where(PRODUCT.id.eq(productId))
                .execute());
        }
        return productId;
    }

    private long findCount(long productId) {
        return queryFactory.select(PRODUCT.likeCount).from(PRODUCT).where(PRODUCT.id.eq(productId)).fetchOne();
    }
}
