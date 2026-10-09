package com.loopers.interfaces.api.admin.product;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.Product;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 잠금 대기 한도(3초)와 잠금 실패 응답을 실제 DB · HTTP 로 확인함 (3주차 설계 4.5).
 * 다른 트랜잭션이 상품 행을 잠근 채로 두고 관리자 재고 변경을 호출함
 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminProductLockTimeoutE2ETest {

    private static final RequestPostProcessor ADMIN = user("admin").roles("ADMIN");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("다른 요청이 상품 행을 잠그고 있으면, 재고 변경은 약 3초 기다린 뒤 500 과 LOCK_ACQUISITION_FAILED 를 돌려주고 재고는 그대로다.")
    @Test
    @Timeout(30)
    void returnsLockAcquisitionFailed_whenRowStaysLockedBeyondWaitLimit() throws Exception {
        // arrange
        Product product = persistProduct(5);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                entityManager.createNativeQuery("select id from product where id = :id for update")
                    .setParameter("id", product.getId())
                    .getSingleResult();
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // act
            long startedAt = System.nanoTime();
            mvc.perform(put("/api-admin/v1/products/" + product.getId() + "/stock")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("stock", 10)))
                    .with(ADMIN).with(csrf()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.meta.errorCode").value("LOCK_ACQUISITION_FAILED"));
            Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            // assert
            assertThat(waited).isBetween(Duration.ofMillis(2_500), Duration.ofSeconds(10));
            assertThat(stockOf(product.getId())).isEqualTo(5);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Product persistProduct(int stock) {
        return transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product product = new Product(brand, "상품", 1_000L);
            product.changeStock(stock);
            entityManager.persist(product);
            return product;
        });
    }

    private int stockOf(Long productId) {
        Number stock = transactionTemplate.execute(status ->
            (Number) entityManager.createNativeQuery("select stock from product where id = :id")
                .setParameter("id", productId)
                .getSingleResult()
        );
        return stock.intValue();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
