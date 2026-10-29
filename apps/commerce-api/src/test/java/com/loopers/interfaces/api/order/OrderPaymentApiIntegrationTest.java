package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.order.OrderApplicationService;
import com.loopers.application.order.PaymentReport;
import com.loopers.application.point.PointApplicationService;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.infrastructure.payment.TestPgServer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.infrastructure.user.UserJpaEntity;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 장면마다 주문 상태·가용 재고·결제 기록·PG 거래 수·create 호출 수·포인트 잔액을 함께 확인한다(포인트는 confirm과 무관해야 한다).
@SpringBootTest
@AutoConfigureMockMvc
class OrderPaymentApiIntegrationTest {
    private static final long BALANCE = 10000;

    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private PointApplicationService points;
    @Autowired private OrderApplicationService orders;
    @Autowired private UserJpaRepository users;
    @Autowired private TestPgFixture pg;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    private TestPgServer server;
    private long first;
    private long second;
    private long orderId;

    @BeforeEach
    void prepare() {
        server = pg.start();
        users.save(new UserJpaEntity(1));
        users.save(new UserJpaEntity(2));
        long brand = brands.create("브랜드").id().value();
        first = products.create(brand, "첫 상품", 2000, 5).id();
        second = products.create(brand, "둘째 상품", 3000, 5).id();
        points.charge(1, BALANCE);
        orderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(first, 2),
            new OrderApplicationService.ItemRequest(second, 1))).id();
    }

    @AfterEach
    void clean() throws InterruptedException {
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("PG가 PENDING으로 접수하면 202·결제 대기·확인 경로를 돌려주고 재고를 확보하며 거래 1건·create 1회·포인트 변화 0이다")
    void acceptsPending() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);

        confirm(1, orderId, UUID.randomUUID().toString())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.id").value(orderId))
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.statusUrl").value("/api/v1/orders/" + orderId))
            .andExpect(jsonPath("$.data.providerStatus").value("PENDING"));

        assertScene("PAYMENT_PENDING", 3, 4, "PENDING", "PENDING", 1, 1);
        assertThat(record().get("transaction_key")).isEqualTo(server.transactions().get(0).transactionKey());
        assertThat(record().get("provider_order_id")).isEqualTo(String.format("LP-ORD-%010d", orderId));
        mvc.perform(get("/api/v1/orders/{id}", orderId).header("X-USER-ID", "1"))
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"))
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
            .andExpect(jsonPath("$.data.paymentResult").value("PENDING"))
            .andExpect(jsonPath("$.data.paidAmount").value(0));
    }

    @Test
    @DisplayName("PG가 SUCCESS로 응답하면 200·CONFIRMED·결제 결과를 돌려주고 확보한 재고를 추가 차감하지 않으며 거래 1건·create 1회다")
    void confirmsOnSuccess() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        confirm(1, orderId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentMethod").value("PG"))
            .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"))
            .andExpect(jsonPath("$.data.paidAmount").value(7000));

        assertScene("CONFIRMED", 3, 4, "SUCCESS", "SUCCESS", 1, 1);
    }

    @Test
    @DisplayName("PG가 202와 FAILED로 접수하면 200 결제 실패로 끝나고 재고 반환은 한 번이며 거래 1건·create 1회다")
    void failsOnAcceptedFailedAndReturnsStockOnce() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_FAILED);
        String key = UUID.randomUUID().toString();

        confirm(1, orderId, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"));
        assertScene("PAYMENT_FAILED", 5, 5, "FAILED", "FAILED", 1, 1);

        confirm(1, orderId, key).andExpect(status().isOk());
        assertScene("PAYMENT_FAILED", 5, 5, "FAILED", "FAILED", 1, 1);
    }

    @Test
    @DisplayName("PG가 효과 없는 거절(422)로 응답하면 200·결제 실패이고 재고를 원래 값으로 한 번 돌려주며 거래 0건·create 1회다")
    void failsOnRejectionAndReturnsStockOnce() throws Exception {
        server.createBehavior(CreateBehavior.REJECT_BEFORE_CREATE);

        confirm(1, orderId, UUID.randomUUID().toString())
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_FAILED"))
            .andExpect(jsonPath("$.data.paymentResult").value("FAILED"))
            .andExpect(jsonPath("$.data.paidAmount").value(0));
        assertScene("PAYMENT_FAILED", 5, 5, "FAILED", "REJECTED_NO_EFFECT", 0, 1);

        // 같은 실패가 다시 보고돼도(조회·callback 중복) 재고를 또 돌려주지 않는다.
        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.REJECTED_NO_EFFECT, null));
        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.FAILED, "TR-LATE"));
        assertScene("PAYMENT_FAILED", 5, 5, "FAILED", "REJECTED_NO_EFFECT", 0, 1);
    }

    @Test
    @DisplayName("PG가 거래를 만든 뒤 응답이 read timeout으로 유실되면 202·결제 대기로 재고 확보를 유지하고 create를 다시 보내지 않는다")
    void keepsPendingWhenResponseLost() throws Exception {
        server.createBehavior(CreateBehavior.DELAY_AFTER_CREATE);

        confirm(1, orderId, UUID.randomUUID().toString())
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));

        assertScene("PAYMENT_PENDING", 3, 4, "PENDING", "UNKNOWN", 1, 1);
        assertThat(record().get("transaction_key")).isNull();
    }

    @Test
    @DisplayName("뒤 품목 재고가 부족하면 409, 상품이 삭제됐으면 404로 거절하고 PG create 0회·재고·주문·결제 기록 변화가 없다")
    void rejectsBeforePgWhenStockShortOrProductDeleted() throws Exception {
        products.setStock(second, 0);
        confirm(1, orderId, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertThat(stock(first)).isEqualTo(5);
        assertThat(stock(second)).isZero();
        assertThat(orderStatus()).isEqualTo("DRAFT");

        products.setStock(second, 5);
        products.delete(second);
        confirm(1, orderId, UUID.randomUUID().toString()).andExpect(status().isNotFound());

        assertThat(stock(first)).isEqualTo(5);
        assertThat(orderStatus()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from payment_records", Long.class)).isZero();
        assertThat(server.createCalls()).isZero();
        assertThat(points.balance(1)).isEqualTo(BALANCE);
    }

    @Test
    @DisplayName("같은 키로 다시 요청하면 결제 대기는 202 지금 상태, 종결은 200 저장 결과를 돌려주고 create를 더 보내지 않으며 다른 키는 409다")
    void returnsCurrentStateForSameKey() throws Exception {
        String key = UUID.randomUUID().toString();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(1, orderId, key).andExpect(status().isAccepted());

        confirm(1, orderId, key)
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.status").value("PAYMENT_PENDING"));
        confirm(1, orderId, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertScene("PAYMENT_PENDING", 3, 4, "PENDING", "PENDING", 1, 1);

        String transactionKey = server.transactions().get(0).transactionKey();
        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS, transactionKey));
        confirm(1, orderId, key)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"));
        confirm(1, orderId, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertScene("CONFIRMED", 3, 4, "SUCCESS", "SUCCESS", 1, 1);
    }

    @Test
    @DisplayName("다른 주문에 이미 쓴 시도 키로 확정하면 409로 거절하고 그 주문의 재고·상태는 그대로다")
    void rejectsAttemptKeyUsedByAnotherOrder() throws Exception {
        String key = UUID.randomUUID().toString();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(1, orderId, key).andExpect(status().isAccepted());
        long otherOrderId = orders.create(1, List.of(new OrderApplicationService.ItemRequest(first, 1))).id();

        confirm(1, otherOrderId, key).andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("select status from orders where id = ?", String.class, otherOrderId)).isEqualTo("DRAFT");
        assertThat(stock(first)).isEqualTo(3);
        assertThat(server.createCalls()).isEqualTo(1);
    }

    @Test
    @DisplayName("타인·없는 주문 확정은 404, 시도 키·카드 정보가 없거나 형식이 틀리면 400이고 PG create는 0회다")
    void rejectsOwnershipAndInvalidInput() throws Exception {
        confirm(2, orderId, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        confirm(1, 999_999, UUID.randomUUID().toString()).andExpect(status().isNotFound());
        for (String body : List.of("{}",
            "{\"paymentAttemptKey\":\"not-a-uuid\",\"cardType\":\"SAMSUNG\",\"cardNo\":\"1234-5678-9814-1451\"}",
            "{\"paymentAttemptKey\":\"" + UUID.randomUUID() + "\",\"cardType\":\"SAMSUNG\"}")) {
            mvc.perform(post("/api/v1/orders/{id}/confirm", orderId).header("X-USER-ID", "1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }

        assertThat(orderStatus()).isEqualTo("DRAFT");
        assertThat(stock(first)).isEqualTo(5);
        assertThat(server.createCalls()).isZero();
    }

    @Test
    @DisplayName("이관된 과거 포인트 주문은 조회 결과가 그대로이고 새 시도 키로 확정하면 409로 거절한다")
    void keepsPastPointOrder() throws Exception {
        jdbc.update("update orders set status = 'CONFIRMED' where id = ?", orderId);
        jdbc.update("insert into payment_records(order_id, method, amount, result) values (?, 'POINT', 7000, 'SUCCESS')", orderId);

        mvc.perform(get("/api/v1/orders/{id}", orderId).header("X-USER-ID", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.paymentMethod").value("POINT"))
            .andExpect(jsonPath("$.data.paymentResult").value("SUCCESS"))
            .andExpect(jsonPath("$.data.paidAmount").value(7000));
        confirm(1, orderId, UUID.randomUUID().toString()).andExpect(status().isConflict());
        assertThat(server.createCalls()).isZero();
    }

    @Test
    @DisplayName("결제 대기 중 상품이 삭제된 뒤 결제가 실패하면 주문은 결제 실패로 끝나고 재고는 돌려주되 삭제 상품은 다시 노출되지 않는다")
    void finishesFailedPaymentForDeletedProduct() throws Exception {
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(1, orderId, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        products.delete(second);

        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.FAILED,
            server.transactions().get(0).transactionKey()));

        assertScene("PAYMENT_FAILED", 5, 5, "FAILED", "FAILED", 1, 1);
        assertThat(jdbc.queryForObject("select deleted from products where id = ?", Boolean.class, second)).isTrue();
        mvc.perform(get("/api/v1/products/{id}", second).header("X-USER-ID", "1")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("결제 대기 중 상품이 삭제돼도 결제가 성공하면 주문은 CONFIRMED로 끝나고 확보한 재고를 그대로 소비한다")
    void finishesSucceededPaymentForDeletedProduct() {
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        orders.confirm(1, orderId, TestPgFixture.command());
        products.delete(second);

        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS,
            server.transactions().get(0).transactionKey()));

        assertScene("CONFIRMED", 3, 4, "SUCCESS", "SUCCESS", 1, 1);
    }

    @Test
    @DisplayName("결제 대기 중 관리자가 재고를 10으로 설정하면 그 값이 예약 제외 가용 수량이고 결제 성공 때 다시 차감하지 않는다")
    void keepsAdminStockAsAvailableQuantity() {
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        orders.confirm(1, orderId, TestPgFixture.command());
        products.setStock(first, 10);

        orders.applyPaymentResult(orderId, new PaymentReport(PaymentRecord.ProviderStatus.SUCCESS,
            server.transactions().get(0).transactionKey()));

        assertScene("CONFIRMED", 10, 4, "SUCCESS", "SUCCESS", 1, 1);
    }

    private ResultActions confirm(long userId, long id, String attemptKey) throws Exception {
        String body = "{\"paymentAttemptKey\":\"" + attemptKey + "\",\"cardType\":\"" + TestPgFixture.CARD_TYPE
            + "\",\"cardNo\":\"" + TestPgFixture.CARD_NO + "\"}";
        return mvc.perform(post("/api/v1/orders/{id}/confirm", id).header("X-USER-ID", String.valueOf(userId))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void assertScene(String orderStatus, int firstStock, int secondStock, String result, String providerStatus,
        int pgTransactions, int createCalls) {
        assertThat(orderStatus()).isEqualTo(orderStatus);
        assertThat(stock(first)).isEqualTo(firstStock);
        assertThat(stock(second)).isEqualTo(secondStock);
        assertThat(record()).containsEntry("method", "PG").containsEntry("result", result)
            .containsEntry("provider_status", providerStatus).containsEntry("amount", 7000L);
        assertThat(server.transactions()).hasSize(pgTransactions);
        assertThat(server.createCalls()).isEqualTo(createCalls);
        assertThat(points.balance(1)).isEqualTo(BALANCE);
    }

    private String orderStatus() {
        return jdbc.queryForObject("select status from orders where id = ?", String.class, orderId);
    }

    private int stock(long productId) {
        return jdbc.queryForObject("select stock from products where id = ?", Integer.class, productId);
    }

    private Map<String, Object> record() {
        return jdbc.queryForMap("select method, amount, result, provider_status, transaction_key, provider_order_id"
            + " from payment_records where order_id = ?", orderId);
    }
}
