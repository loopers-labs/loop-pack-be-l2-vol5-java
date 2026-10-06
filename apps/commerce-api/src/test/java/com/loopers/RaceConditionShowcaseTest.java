package com.loopers;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.point.application.PointQueryService;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;


@SpringBootTest
public class RaceConditionShowcaseTest {

    private static final String SUCCESS = "성공";
    private static final String OUT_OF_STOCK = "재고가 부족합니다";
    private static final String NOT_ENOUGH_POINTS = "포인트 잔액이 부족합니다";

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private PointQueryService pointQueryService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private BrandModel nike;

    @BeforeEach
    void setUp() {
        nike = brandJpaRepository.save(new BrandModel("나이키", null));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("[재고] 재고 100, 서로 다른 100명이 1개씩 동시에 주문 확정 → 100건 모두 성공, 재고 0")
    @Test
    void stock_100Buyers_100Stock() {
        // given
        ProductModel product = saveProduct("에어맥스", 1_000, 100);
        List<UserModel> buyers = saveBuyers(100, 10_000);
        List<Long> orderIds = createOrders(buyers, product);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        // when
        try (ExecutorService executorService = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < 100; i++) {
                int index = i;
                executorService.submit(() -> {
                    try {
                        orderCommandUseCase.confirm(buyers.get(index).getId(), orderIds.get(index));
                        results.add(SUCCESS);
                    } catch (Exception e) {
                        results.add(e.getMessage());
                    }
                });
            }
        }

        // then: 100 - 1 * 100 = 0
        print("재고 100 · 100명", results, "최종 재고 " + stockOf(product));
        assertThat(count(results, SUCCESS)).isEqualTo(100);
        assertThat(stockOf(product)).isZero();

        /** Failed
         * Expected :0
         * Actual   :89
         */
    }

    @DisplayName("[재고] 재고 100, 서로 다른 150명이 1개씩 동시에 주문 확정 → 성공 100 · 재고 부족 50, 재고는 0이고 음수가 되지 않는다")
    @Test
    void stock_150Buyers_100Stock() {
        // given
        ProductModel product = saveProduct("에어맥스", 1_000, 100);
        List<UserModel> buyers = saveBuyers(150, 10_000);
        List<Long> orderIds = createOrders(buyers, product);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        // when
        try (ExecutorService executorService = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < 150; i++) {
                int index = i;
                executorService.submit(() -> {
                    try {
                        orderCommandUseCase.confirm(buyers.get(index).getId(), orderIds.get(index));
                        results.add(SUCCESS);
                    } catch (Exception e) {
                        results.add(e.getMessage());
                    }
                });
            }
        }

        // then: 산 사람만 1,000원이 빠졌다
        print("재고 100 · 150명", results, "최종 재고 " + stockOf(product));
        assertThat(count(results, SUCCESS)).isEqualTo(100);
        assertThat(count(results, OUT_OF_STOCK)).isEqualTo(50);
        assertThat(stockOf(product)).isZero();
        assertThat(buyers.stream().filter(buyer -> balanceOf(buyer) == 9_000).count()).isEqualTo(100);
        assertThat(buyers.stream().filter(buyer -> balanceOf(buyer) == 10_000).count()).isEqualTo(50);

        /** Failed
         * ▶ 재고 100 · 150명
         *    요청 150 = 성공 150 + 재고 부족 0 + 잔액 부족 0 + 그 밖의 오류 0
         *    최종 재고 84
         * */
    }

    @DisplayName("[포인트] 한 사용자 잔액 100,000원, 1,000원 주문 100건을 동시에 확정 → 100건 모두 성공, 잔액 0")
    @Test
    void point_100Orders_exactBalance() {
        // given: 재고는 넉넉히 두어 잔액만 경쟁하게 한다
        ProductModel product = saveProduct("양말", 1_000, 1_000);
        UserModel buyer = saveBuyers(1, 100_000).get(0);
        List<Long> orderIds = createOrders(Collections.nCopies(100, buyer), product);
        List<String> results = Collections.synchronizedList(new ArrayList<>());

        // when
        try (ExecutorService executorService = Executors.newFixedThreadPool(32)) {
            for (int i = 0; i < 100; i++) {
                int index = i;
                executorService.submit(() -> {
                    try {
                        orderCommandUseCase.confirm(buyer.getId(), orderIds.get(index));
                        results.add(SUCCESS);
                    } catch (Exception e) {
                        results.add(e.getMessage());
                    }
                });
            }
        }

        // then: 100,000 - 1,000 * 100 = 0
        print("잔액 100,000 · 1,000원 주문 100건", results, "최종 잔액 " + balanceOf(buyer));
        assertThat(count(results, SUCCESS)).isEqualTo(100);
        assertThat(balanceOf(buyer)).isZero();

        /** Failed
         * Expected :0L
         * Actual   :89000L
         * */
    }


    private List<UserModel> saveBuyers(int count, long balance) {
        List<UserModel> buyers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UserModel buyer = userJpaRepository.save(new UserModel("구매자" + i));
            pointCommandUseCase.charge(buyer.getId(), balance);
            buyers.add(buyer);
        }
        return buyers;
    }


    private static void print(String title, List<String> results, String finalState) {
        long rejected = count(results, OUT_OF_STOCK) + count(results, NOT_ENOUGH_POINTS);
        long success = count(results, SUCCESS);
        System.out.printf("%n▶ %s%n   요청 %d = 성공 %d + 재고 부족 %d + 잔액 부족 %d + 그 밖의 오류 %d%n   %s%n%n",
                title, results.size(), success, count(results, OUT_OF_STOCK), count(results, NOT_ENOUGH_POINTS),
                results.size() - success - rejected, finalState);
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }


    private ProductModel saveProduct(String name, long price, int stock) {
        return productJpaRepository.save(new ProductModel(nike.getId(), name, price, stock));
    }

    private static long count(List<String> results, String label) {
        return results.stream().filter(result -> result.contains(label)).count();
    }

    private List<Long> createOrders(List<UserModel> buyers, ProductModel product) {
        return buyers.stream()
                .map(buyer -> orderCommandUseCase.create(buyer.getId(), List.of(new OrderLines.Line(product.getId(), 1))).id())
                .toList();
    }

    private long balanceOf(UserModel user) {
        return pointQueryService.getBalance(user.getId()).balance();
    }

}
