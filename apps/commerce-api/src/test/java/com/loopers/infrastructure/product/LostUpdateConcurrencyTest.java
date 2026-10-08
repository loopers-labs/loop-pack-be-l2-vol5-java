package com.loopers.infrastructure.product;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.application.brand.fixture.BrandFixture;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.infrastructure.product.fixture.ProductFixture;
import com.loopers.support.ConcurrentRequests;
import com.loopers.utils.DatabaseCleanUp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootTest
class LostUpdateConcurrencyTest {
    @Autowired private BrandFixture brands;
    @Autowired private ProductFixture fixture;
    @Autowired private ProductRepository products;
    @Autowired private TransactionTemplate transactions;
    @Autowired private DatabaseCleanUp cleanUp;

    @Test
    void 두_요청이_재고_5를_읽고_각각_4를_저장하면_한_번의_차감이_유실된다() throws Exception {
        // arrange: 의도적으로 보호가 없는 읽기·쓰기를 사용하는 대조군이다.
        long brandId = brands.createBrand().getId();
        long productId = fixture.createProduct(brandId, "상품", 1_000, 5).getId();
        CountDownLatch bothRead = new CountDownLatch(2);
        Callable<Integer> deduction = () -> deductWithoutProtection(productId, bothRead);

        // act
        var result = ConcurrentRequests.run(List.of(deduction, deduction));

        // assert: 잘못된 결과가 재현됐음을 확인하므로 이 테스트 자체는 통과한다.
        assertThat(result.failures()).isEmpty();
        assertThat(result.successes()).containsExactly(5, 5);
        int remaining = fixture.product(productId).getStock();
        assertThat(remaining).isEqualTo(4);
        assertThat(remaining + result.successes().size()).isNotEqualTo(5);
    }

    private int deductWithoutProtection(long productId, CountDownLatch bothRead) {
        return transactions.execute(
                status -> {
                    Product product = products.findById(productId).orElseThrow();
                    int stock = product.getStock();
                    awaitBothReads(bothRead);
                    // 대조군에서는 읽어 둔 값에서 계산한 상수를 일반 저장으로 덮어쓴다.
                    // 운영 차감 경로의 조건부 UPDATE는 사용하지 않는다.
                    product.setStock(stock - 1);
                    products.save(product);
                    return stock;
                });
    }

    private void awaitBothReads(CountDownLatch bothRead) {
        bothRead.countDown();
        try {
            if (!bothRead.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 재고 읽기 완료 시간 초과");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재고 읽기 대기 중 인터럽트", error);
        }
    }

    @AfterEach
    void cleanDatabase() {
        cleanUp.deleteAllEntities();
    }
}
