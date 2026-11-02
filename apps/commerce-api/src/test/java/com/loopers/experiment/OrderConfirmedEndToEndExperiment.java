package com.loopers.experiment;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.pg.TestPgServer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 6주차 설계 7.2 의 정상 사례. 실제 확정 · PG fixture · 결과 적용 경로로 확정한 주문이 로컬 compose broker 를 지나
 * 따로 띄운 commerce-streamer 가 구매 활동으로 남기는지 같은 MySQL 에서 확인함. 실행 절차는 docs/week6/experiment-delivery.md.
 * 로컬 DB 를 지우지 않도록 ddl 은 update 로 돌리고 정리(truncate)하지 않음
 */
@EnabledIfEnvironmentVariable(named = "TEST_LOCAL_INFRA", matches = "true")
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=update",
    // 로컬 3306 을 다른 MySQL 이 쓰고 있으면 임시 컨테이너 주소를 넘김 (실행 절차 참고)
    "datasource.mysql-jpa.main.jdbc-url=${E2E_JDBC_URL:jdbc:mysql://localhost:3306/loopers}",
    // test profile 은 계정을 Testcontainers 가 넣으므로 compose 계정을 직접 줌
    "datasource.mysql-jpa.main.username=application",
    "datasource.mysql-jpa.main.password=application"
})
class OrderConfirmedEndToEndExperiment {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedEndToEndExperiment.class);
    private static final Long USER_ID = 1L;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final TestPgServer pg = TestPgServer.shared();

    private List<?> query(String sql) {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return reader.createNativeQuery(sql).getResultList();
        } finally {
            reader.close();
        }
    }

    @DisplayName("PG 로 확정한 주문이 broker 와 commerce-streamer 를 지나 품목별 구매 활동으로 남는다.")
    @Test
    void confirmedOrderBecomesPurchaseActivity() throws InterruptedException {
        // arrange
        pg.reset();
        Order order = transactionTemplate.execute(status -> {
            Brand brand = new Brand("E2E 브랜드", null);
            entityManager.persist(brand);
            Product first = new Product(brand, "E2E 상품 A", 1_000L);
            first.changeStock(10);
            entityManager.persist(first);
            Product second = new Product(brand, "E2E 상품 B", 2_000L);
            second.changeStock(10);
            entityManager.persist(second);
            Order draft = Order.draft(USER_ID, List.of(
                new OrderLine(first.getId(), first.getName(), first.getPrice(), 2),
                new OrderLine(second.getId(), second.getName(), second.getPrice(), 1)
            ), ZonedDateTime.now());
            entityManager.persist(draft);
            return draft;
        });
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("e2e-" + order.getId() + "-" + System.nanoTime(), "TEST", "0000-0000-0000-0000"));
        String transactionKey = pg.transactionsOf(Payment.providerOrderIdOf(order.getId())).get(0).transactionKey();
        pg.complete(transactionKey, "SUCCESS");

        // act
        OrderInfo confirmed = orderPaymentFacade.reconcile(USER_ID, order.getId());

        // assert: streamer 가 저장할 때까지 유한하게 기다림
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        List<?> processed = List.of();
        while (processed.isEmpty() && Instant.now().isBefore(deadline)) {
            processed = query("select event_id, confirmed_at from processed_order_confirmation where order_id = " + order.getId());
            Thread.sleep(200);
        }
        List<?> activities = query("select product_id, quantity from product_purchase_activity where order_id = " + order.getId() + " order by product_id");
        List<?> orderRow = query("select status, confirmed_at from orders where id = " + order.getId());
        log.info("[7.2 정상 E2E] orderId={} providerOrderId={} transactionKey={} 주문={} 처리 기록={} 구매 활동={}",
            order.getId(), Payment.providerOrderIdOf(order.getId()), transactionKey, describe(orderRow), describe(processed), describe(activities));
        assertThat(confirmed.status()).isEqualTo("CONFIRMED");
        assertThat(processed).as("streamer 가 처리 기록을 남김").hasSize(1);
        assertThat(activities).hasSize(2);
    }

    private static String describe(List<?> rows) {
        return rows.stream().map(row -> row instanceof Object[] columns ? List.of(columns).toString() : String.valueOf(row)).toList().toString();
    }
}
