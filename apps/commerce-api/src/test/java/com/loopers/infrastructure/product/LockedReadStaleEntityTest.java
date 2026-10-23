package com.loopers.infrastructure.product;

import com.loopers.domain.common.Money;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.Stock;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "트랜잭션에서 대상 행을 처음 읽는 조회가 잠금 조회여야 한다"는 규칙의 JPA 쪽 근거를 확인하는 실험이다.
 *
 * <p>영속성 컨텍스트는 트랜잭션마다 하나이고, 이미 읽은 엔티티는 1차 캐시에 남는다. 잠금 없이 먼저 읽은 상품을 같은
 * 트랜잭션에서 나중에 {@code FOR UPDATE} 로 다시 조회하면, DB 는 최신 행을 돌려주지만 Hibernate 는 이미 관리 중인 객체를
 * 그대로 돌려주고 새로 읽은 컬럼 값을 버린다. 그 객체에 변경을 더해 저장하면 전체 컬럼 UPDATE 가 최신 값을 덮는다.
 * 이 테스트가 통과하는 동안에는 "처음 읽기가 잠금 읽기여야 한다"는 규칙을 지켜야 한다.
 */
@SpringBootTest
class LockedReadStaleEntityTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("일반 조회로 먼저 읽은 상품을 같은 트랜잭션에서 잠금 조회하면, 잠금은 잡히지만 엔티티는 낡은 값 그대로다.")
    @Test
    void returnsStaleEntity_whenAlreadyLoadedWithoutLock() {
        // arrange
        Product saved = productRepository.save(Product.create(1L, "러닝화", new Money(89_000), new Stock(5)));
        Long productId = saved.getId();

        // act & assert
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Product loadedWithoutLock = productRepository.findActive(productId).orElseThrow();
            assertThat(loadedWithoutLock.getStock().remaining()).isEqualTo(5);

            // 다른 트랜잭션이 재고를 3 으로 바꾸고 commit 한다.
            TransactionTemplate other = new TransactionTemplate(transactionManager);
            other.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            other.executeWithoutResult(inner ->
                jdbcTemplate.update("update products set stock = 3 where id = ?", productId));

            Product loadedWithLock = productRepository.findAllActiveByIdsForUpdate(List.of(productId)).get(0);

            // DB 에는 최신 값 3 이 있지만, 같은 트랜잭션의 영속성 컨텍스트는 먼저 읽은 객체(재고 5)를 돌려준다.
            assertThat(loadedWithLock).isSameAs(loadedWithoutLock);
            assertThat(loadedWithLock.getStock().remaining()).isEqualTo(5);
            assertThat(latestStockFromDatabase(productId)).isEqualTo(3);
        });
    }

    @DisplayName("처음 읽기를 잠금 조회로 하면 최신 commit 값을 읽는다.")
    @Test
    void returnsLatestEntity_whenFirstReadIsLockedRead() {
        // arrange
        Product saved = productRepository.save(Product.create(1L, "러닝화", new Money(89_000), new Stock(5)));
        Long productId = saved.getId();
        jdbcTemplate.update("update products set stock = 3 where id = ?", productId);

        // act
        Product loaded = new TransactionTemplate(transactionManager).execute(status ->
            productRepository.findAllActiveByIdsForUpdate(List.of(productId)).get(0));

        // assert
        assertThat(loaded.getStock().remaining()).isEqualTo(3);
    }

    private int latestStockFromDatabase(Long productId) {
        return jdbcTemplate.queryForObject(
            "select stock from products where id = ? for update", Integer.class, productId);
    }
}
