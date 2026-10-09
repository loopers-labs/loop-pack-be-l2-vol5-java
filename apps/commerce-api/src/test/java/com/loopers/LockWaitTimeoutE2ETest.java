package com.loopers;

import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3 ADR-W3-04: 다른 트랜잭션이 상품 행을 잠근 채 끝나지 않으면, 잠금 경로는 3초 뒤 500이 아니라
 * 409 + Concurrency Conflict로 답하고 아무것도 바꾸지 않는다. 재고 부족(Conflict)과 errorCode가 다르다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LockWaitTimeoutE2ETest {

    private static final String USER_HEADER = "X-USER-ID";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품 행이 잠긴 동안 확정·관리자 재고 설정은 409 Concurrency Conflict이고 재고·잔액·주문이 그대로다. 잠금이 풀린 뒤 다시 확정하면 성공한다.")
    @Test
    void respondsConcurrencyConflict_whenLockWaitTimesOut() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, buyer.getId())
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10000}"))
            .andExpect(status().isOk());
        String created = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, buyer.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": " + airMax.getId() + ", \"quantity\": 1}]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            // 다른 트랜잭션이 상품 행을 잠근 채 머문다 (테스트에서만 만드는 상황)
            Future<?> holding = holder.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM products WHERE id = ? FOR UPDATE", Long.class, airMax.getId());
                locked.countDown();
                awaitQuietly(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            // act & assert: 잠금 대기 3초를 넘기면 혼잡으로 답한다
            mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header(USER_HEADER, buyer.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Concurrency Conflict"));
            mockMvc.perform(put("/api-admin/v1/products/" + airMax.getId() + "/stock")
                    .with(user("admin").roles("ADMIN")).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"stock\": 100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.meta.errorCode").value("Concurrency Conflict"));

            release.countDown();
            holding.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
            holder.awaitTermination(5, TimeUnit.SECONDS);
        }

        // assert: 아무것도 바뀌지 않았다
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(5);
        mockMvc.perform(get("/api/v1/orders/" + orderId).header(USER_HEADER, buyer.getId()))
            .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, buyer.getId()))
            .andExpect(jsonPath("$.data.balance").value(10_000));

        // act & assert: 잠금이 풀린 뒤 다시 시도하면 성공한다
        mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header(USER_HEADER, buyer.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
        assertThat(productJpaRepository.findById(airMax.getId()).orElseThrow().getStock()).isEqualTo(4);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
