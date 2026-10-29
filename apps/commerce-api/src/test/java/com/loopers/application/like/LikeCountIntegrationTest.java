package com.loopers.application.like;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.OrderResult;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.utils.DatabaseCleanUp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

// 서비스 호출이 각자 트랜잭션을 끝낸 뒤 JdbcTemplate(자동 커밋)으로 원본 관계 수와 like_count를 새로 읽어 비교한다.
@SpringBootTest
class LikeCountIntegrationTest {
    private static final long TASK_TIMEOUT_SECONDS = 30;

    @Autowired private BrandApplicationService brandApplicationService;
    @Autowired private ProductApplicationService productApplicationService;
    @Autowired private LikeApplicationService likeApplicationService;
    @Autowired private PointApplicationService pointApplicationService;
    @Autowired private OrderApplicationService orderApplicationService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private long productId;

    @BeforeEach
    void prepare() {
        long brandId = brandApplicationService.create("브랜드").id().value();
        productId = productApplicationService.create(brandId, "상품", 1000, 10).id();
    }

    @AfterEach
    void clean() {
        jdbcTemplate.update("delete from order_items");
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("좋아요를 한 번 등록하면 관계 1건이 생기고 like_count가 1 증가한다")
    void incrementsOnRegister() {
        likeApplicationService.register(1, productId);

        assertThat(relationCount()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사용자가 다시 등록하면 오류 없이 끝나고 관계와 like_count는 바뀌지 않는다")
    void ignoresDuplicateRegister() {
        likeApplicationService.register(1, productId);

        likeApplicationService.register(1, productId);

        assertThat(relationCount()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("좋아요를 취소하면 like_count가 1 감소하고 없는 관계를 취소하면 오류 없이 변화가 없다")
    void decrementsOnlyOnActualCancel() {
        likeApplicationService.register(1, productId);
        likeApplicationService.register(2, productId);

        likeApplicationService.cancel(1, productId);
        assertThat(relationCount()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);

        likeApplicationService.cancel(1, productId);
        likeApplicationService.cancel(3, productId);
        assertThat(relationCount()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("상품 수정과 재고 설정과 주문 확정으로 상품을 저장해도 like_count는 덮어써지지 않는다")
    void keepsLikeCountAcrossProductSaves() {
        likeApplicationService.register(1, productId);
        likeApplicationService.register(2, productId);

        productApplicationService.change(productId, "수정된 상품", 2000);
        productApplicationService.setStock(productId, 5);
        pointApplicationService.charge(1, 10000);
        OrderResult draft = orderApplicationService.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 1)));
        orderApplicationService.confirm(1, draft.id());

        assertThat(productApplicationService.getAdminProduct(productId).stock()).isEqualTo(4);
        assertThat(relationCount()).isEqualTo(2);
        assertThat(likeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("여러 사용자가 같은 상품에 동시에 등록과 취소를 하면 모두 성공하고 like_count는 남은 관계 수와 같다")
    void keepsLikeCountUnderConcurrentRegisterAndCancel() throws Exception {
        for (long userId = 1; userId <= 5; userId++) {
            likeApplicationService.register(userId, productId);
        }
        List<Callable<Void>> tasks = new ArrayList<>();
        for (long userId = 1; userId <= 5; userId++) {
            long canceller = userId;
            long registrant = userId + 5;
            tasks.add(() -> {
                likeApplicationService.cancel(canceller, productId);
                return null;
            });
            tasks.add(() -> {
                likeApplicationService.register(registrant, productId);
                return null;
            });
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).as(technicalErrors(outcomes)).isEqualTo(10);
        assertThat(counts.get(Result.BUSINESS_REJECTION)).isZero();
        assertThat(counts.get(Result.TECHNICAL_ERROR)).isZero();
        assertThat(relationCount()).isEqualTo(5);
        assertThat(likeCount()).isEqualTo(relationCount());
    }

    @Test
    @DisplayName("같은 사용자가 같은 상품에 동시에 여러 번 등록하면 관계는 1건이고 like_count는 1만 증가한다")
    void countsConcurrentDuplicateRegisterOnce() throws Exception {
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> {
                likeApplicationService.register(1, productId);
                return null;
            });
        }

        List<Outcome> outcomes = runConcurrently(tasks);

        Map<Result, Long> counts = count(outcomes);
        assertThat(counts.get(Result.SUCCESS)).as(technicalErrors(outcomes)).isEqualTo(8);
        assertThat(counts.get(Result.TECHNICAL_ERROR)).isZero();
        assertThat(relationCount()).isEqualTo(1);
        assertThat(likeCount()).isEqualTo(1);
    }

    private long relationCount() {
        return jdbcTemplate.queryForObject("select count(*) from product_likes where product_id = ?", Long.class, productId);
    }

    private long likeCount() {
        return jdbcTemplate.queryForObject("select like_count from products where id = ?", Long.class, productId);
    }

    // 시작 시점만 맞추고 실제 서비스 경로에는 장벽·sleep을 넣지 않는다. 모든 요청 결과를 성공·업무 거절·기술 오류로 수집한다.
    private List<Outcome> runConcurrently(List<Callable<Void>> tasks) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Outcome>> futures = tasks.stream()
                .map(task -> executor.submit(() -> {
                    if (!start.await(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        return new Outcome(Result.TECHNICAL_ERROR, "시작 신호 대기 시간 초과");
                    }
                    return execute(task);
                }))
                .toList();
            start.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(await(future));
            }
            assertThat(outcomes).hasSize(tasks.size());
            return outcomes;
        } finally {
            start.countDown();
            executor.shutdownNow();
            executor.awaitTermination(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private Outcome execute(Callable<Void> task) {
        try {
            task.call();
            return new Outcome(Result.SUCCESS, null);
        } catch (RuleViolationException e) {
            return new Outcome(Result.BUSINESS_REJECTION, e.getMessage());
        } catch (Exception e) {
            return new Outcome(Result.TECHNICAL_ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private Outcome await(Future<Outcome> future) throws InterruptedException {
        try {
            return future.get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return new Outcome(Result.TECHNICAL_ERROR, "완료 대기 실패: " + e);
        }
    }

    private Map<Result, Long> count(List<Outcome> outcomes) {
        Map<Result, Long> counts = outcomes.stream()
            .collect(Collectors.groupingBy(Outcome::result, Collectors.counting()));
        for (Result result : Result.values()) {
            counts.putIfAbsent(result, 0L);
        }
        assertThat(counts.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(outcomes.size());
        return counts;
    }

    private String technicalErrors(List<Outcome> outcomes) {
        return outcomes.stream()
            .filter(outcome -> outcome.result() != Result.SUCCESS)
            .map(Outcome::message)
            .collect(Collectors.joining(", ", "실패: [", "]"));
    }

    private enum Result {
        SUCCESS,
        BUSINESS_REJECTION,
        TECHNICAL_ERROR
    }

    private record Outcome(Result result, String message) { }
}
