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
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * W3 ADR-W3-04·R-8: 교착(MySQL 1213)으로 확정 트랜잭션이 희생되면 500이 아니라 409 Concurrency Conflict이고 아무것도 바뀌지 않는다.
 * 테스트 트랜잭션이 상품 B → A 순서로 잠가 확정(A → B)과 교착을 만든다. InnoDB는 바꾼 행이 적은 쪽을 희생시키므로,
 * 테스트 트랜잭션이 먼저 다른 테이블에 여러 행을 추가해 무겁게 만든다 (실습 practice-locks.md 실험 1).
 */
@SpringBootTest
@AutoConfigureMockMvc
class DeadlockE2ETest {

    private static final String USER_HEADER = "X-USER-ID";
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);

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

    @DisplayName("ADR-W3-04 확정이 교착의 희생자가 되면 409 Concurrency Conflict이고, 재고·잔액·주문 상태가 그대로다")
    @Test
    void respondsConcurrencyConflict_whenConfirmIsDeadlockVictim() throws Exception {
        // arrange
        UserModel buyer = userJpaRepository.save(new UserModel("고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        ProductModel first = productJpaRepository.save(new ProductModel(nike.getId(), "상품 A", 1_000, 5));
        ProductModel second = productJpaRepository.save(new ProductModel(nike.getId(), "상품 B", 1_000, 5));
        mockMvc.perform(post("/api/v1/points/charge").header(USER_HEADER, buyer.getId())
            .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 10000}"));
        String created = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, buyer.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": " + first.getId() + ", \"quantity\": 1},"
                    + " {\"productId\": " + second.getId() + ", \"quantity\": 1}]}"))
            .andReturn().getResponse().getContentAsString();
        Long orderId = JsonPath.parse(created).read("$.data.id", Long.class);

        CountDownLatch holdingSecond = new CountDownLatch(1);
        CountDownLatch requestFirst = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 테스트 트랜잭션: 무겁게 만든 뒤 B를 잠그고, 확정이 A를 잡았다는 신호를 받으면 A를 요청한다 → 교착
            Future<?> holder = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                // 다른 테이블에 행을 여러 개 추가해 무겁게 만든다. 상품을 UPDATE로 무겁게 하면, 행이 적은 테이블은 전체 스캔이 되어
                // REPEATABLE READ에서 스캔한 A·B까지 미리 잠가 버린다 (구현 중 실제로 겪은 일 — plan.md 5장 AI 검토 기록)
                for (int i = 0; i < 50; i++) {
                    jdbcTemplate.update("INSERT INTO brands (name, created_at, updated_at) VALUES (?, NOW(), NOW())", "채우기 " + i);
                }
                jdbcTemplate.queryForObject("SELECT id FROM products WHERE id = ? FOR UPDATE", Long.class, second.getId());
                holdingSecond.countDown();
                awaitQuietly(requestFirst);
                jdbcTemplate.queryForObject("SELECT id FROM products WHERE id = ? FOR UPDATE", Long.class, first.getId());
                status.setRollbackOnly();
            }));
            assertThat(holdingSecond.await(WAIT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

            // act: 확정은 A를 잠근 뒤 B에서 기다린다
            Future<MvcResult> confirm = executor.submit(() ->
                mockMvc.perform(post("/api/v1/orders/" + orderId + "/confirm").header(USER_HEADER, buyer.getId())).andReturn());
            waitUntilLockedByOther(first.getId());
            requestFirst.countDown();
            MvcResult result = confirm.get(10, TimeUnit.SECONDS);
            holder.get(10, TimeUnit.SECONDS);

            // assert: 교착 희생은 혼잡으로 답한다
            assertThat(result.getResponse().getStatus()).isEqualTo(409);
            assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.meta.errorCode"))
                .isEqualTo("Concurrency Conflict");
        } finally {
            requestFirst.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        // assert: 아무것도 바뀌지 않았다
        assertThat(productJpaRepository.findById(first.getId()).orElseThrow().getStock()).isEqualTo(5);
        assertThat(productJpaRepository.findById(second.getId()).orElseThrow().getStock()).isEqualTo(5);
        mockMvc.perform(get("/api/v1/orders/" + orderId).header(USER_HEADER, buyer.getId()))
            .andExpect(jsonPath("$.data.status").value("DRAFT"));
        mockMvc.perform(get("/api/v1/points").header(USER_HEADER, buyer.getId()))
            .andExpect(jsonPath("$.data.balance").value(10_000));
    }

    /**
     * 확정이 A를 잠갔는지 별도의 짧은 트랜잭션에서 NOWAIT로 확인한다(잠겨 있으면 즉시 3572 오류, 비어 있으면 잡았다가 바로 롤백해 놓아준다).
     * 우연을 기다리는 sleep이 아니라 조건이 참이 될 때까지의 확인이며, 제한 시간이 있다.
     */
    private void waitUntilLockedByOther(Long productId) {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            Boolean locked = transactionTemplate.execute(status -> {
                status.setRollbackOnly();
                try {
                    jdbcTemplate.queryForObject("SELECT id FROM products WHERE id = ? FOR UPDATE NOWAIT", Long.class, productId);
                    return false;
                } catch (DataAccessException lockedByOther) {
                    return true;
                }
            });
            if (Boolean.TRUE.equals(locked)) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
        }
        throw new IllegalStateException("확정이 A를 잠그지 않았습니다");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
