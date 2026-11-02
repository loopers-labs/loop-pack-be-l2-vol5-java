package com.loopers.application.order.listener;

import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/** send 실패가 이미 commit 된 확정을 되돌리거나 응답을 실패로 만들지 않고 기록으로만 드러나는지 봄 (6주차 D-73, 설계 3.4) */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest
class OrderConfirmedPublishFailureTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @MockitoSpyBean
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();
    private Order order;

    @BeforeEach
    void setUp() {
        pg.reset();
        order = transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product product = new Product(brand, "상품", 1_000L);
            product.changeStock(10);
            entityManager.persist(product);
            Order draft = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), 1)),
                ZonedDateTime.now());
            entityManager.persist(draft);
            return draft;
        });
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
        List<TestPgServer.Transaction> transactions = pg.transactionsOf(Payment.providerOrderIdOf(order.getId()));
        pg.complete(transactions.get(0).transactionKey(), "SUCCESS");
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private String orderStatus() {
        return (String) transactionTemplate.execute(status ->
            entityManager.createNativeQuery("select status from orders where id = " + order.getId()).getSingleResult());
    }

    @DisplayName("broker 가 전송을 실패로 끝내도, 주문은 확정된 채 응답하고 PG 를 다시 부르지 않으며 실패를 eventId 와 함께 기록한다.")
    @Test
    void keepsConfirmed_whenSendFails(CapturedOutput output) {
        // arrange
        doReturn(CompletableFuture.failedFuture(new KafkaException("주입한 전송 실패")))
            .when(kafkaTemplate).send(anyString(), any(), any());

        int createsBefore = pg.createCount();

        // act
        OrderInfo result = orderPaymentFacade.reconcile(USER_ID, order.getId());

        // assert
        assertAll(
            () -> assertThat(result.status()).isEqualTo("CONFIRMED"),
            () -> assertThat(orderStatus()).isEqualTo("CONFIRMED"),
            // 발행 실패를 결제 실패로 다루지 않아 PG 생성을 다시 보내지 않음 (D-60)
            () -> assertThat(pg.createCount()).isEqualTo(createsBefore),
            () -> assertThat(output).contains("주문 확정 이벤트 발행 실패. eventId=order-confirmed:v1:" + order.getId())
        );
    }

    @DisplayName("send 호출 자체가 예외를 던져도, 주문은 확정된 채 응답하고 PG 를 다시 부르지 않으며 실패를 기록한다.")
    @Test
    void keepsConfirmed_whenSendThrows(CapturedOutput output) {
        // arrange
        doThrow(new KafkaException("주입한 send 예외")).when(kafkaTemplate).send(anyString(), any(), any());

        int createsBefore = pg.createCount();

        // act
        OrderInfo result = orderPaymentFacade.reconcile(USER_ID, order.getId());

        // assert
        assertAll(
            () -> assertThat(result.status()).isEqualTo("CONFIRMED"),
            () -> assertThat(orderStatus()).isEqualTo("CONFIRMED"),
            // 발행 실패를 결제 실패로 다루지 않아 PG 생성을 다시 보내지 않음 (D-60)
            () -> assertThat(pg.createCount()).isEqualTo(createsBefore),
            () -> assertThat(output).contains("주문 확정 이벤트 발행 실패. eventId=order-confirmed:v1:" + order.getId())
        );
    }
}
