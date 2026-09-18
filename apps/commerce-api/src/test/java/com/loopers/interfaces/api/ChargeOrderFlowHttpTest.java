package com.loopers.interfaces.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.loopers.brand.domain.Brand;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import static com.loopers.support.http.ApiHttp.admin;
import static com.loopers.support.http.ApiHttp.customer;
import static com.loopers.support.http.ApiHttp.data;
import static com.loopers.support.http.ApiHttp.findById;
import static com.loopers.support.http.ApiHttp.findByProductId;
import static com.loopers.support.http.ApiHttp.success;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 충전 API → 여러 품목 주문 확정 → 내 주문·잔액 조회 흐름이다.
 * 잔액 0에서 10,000을 충전하고, 상품 A(2,000원·재고 5) 2개와 B(3,000원·재고 3) 1개를 7,000원에 확정한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlTestContainersConfig.class)
class ChargeOrderFlowHttpTest {

    private static final String CHARGE = "/api/v1/points/charge";
    private static final String POINTS = "/api/v1/points";
    private static final String ORDERS = "/api/v1/orders";
    private static final String ORDER = "/api/v1/orders/{orderId}";
    private static final String CONFIRM = "/api/v1/orders/{orderId}/confirm";
    private static final String ADMIN_PRODUCT = "/api-admin/v1/products/{productId}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @DisplayName("[R-POINT-06] 충전에 성공하면 기존 잔액에 충전액을 더하고 충전 후 잔액을 제공한다.")
    @Nested
    class ChargeInFlow {

        @DisplayName("[상태 전이] 흐름의 첫 단계에서 잔액 0에 10,000을 충전하면 충전 후 잔액 10,000을 응답한다.")
        @Test
        void returnsChargedBalance() throws Exception {
            // act
            Flow flow = runFlow();

            // assert
            assertThat(data(flow.charged()).path("balance").asLong()).isEqualTo(10_000L);
        }
    }

    @DisplayName("[R-POINT-02] 고객은 자신의 저장된 포인트 잔액을 조회할 수 있다.")
    @Nested
    class BalanceInFlow {

        @DisplayName("[상태 전이] 잔액 조회는 충전 뒤 10,000, 주문 확정 뒤 3,000으로 그때 저장된 잔액을 준다.")
        @Test
        void readsBalanceAtEachStep() throws Exception {
            // arrange
            Setup setup = setup();

            // act
            charge(setup.customer());
            long afterCharge = balanceOf(setup.customer());
            long orderId = data(createOrder(setup)).path("id").asLong();
            confirm(setup.customer(), orderId);
            long afterConfirm = balanceOf(setup.customer());

            // assert
            assertAll(
                () -> assertThat(afterCharge).isEqualTo(10_000L),
                () -> assertThat(afterConfirm).isEqualTo(3_000L)
            );
        }
    }

    @DisplayName("[R-ORDER-11] 주문 확정에 성공하면 상품 재고와 고객 포인트를 차감한다.")
    @Nested
    class DeductInFlow {

        @DisplayName("[상태 전이] 주문을 확정하면 재고 A는 5에서 3, B는 3에서 2가 되고 잔액은 10,000에서 3,000이 된다.")
        @Test
        void deductsStockAndPoint() throws Exception {
            // act
            Flow flow = runFlow();

            // assert
            assertAll(
                () -> assertThat(stockOf(flow.setup().a())).isEqualTo(3),
                () -> assertThat(stockOf(flow.setup().b())).isEqualTo(2),
                () -> assertThat(balanceOf(flow.setup().customer())).isEqualTo(3_000L)
            );
        }
    }

    @DisplayName("[R-ORDER-12] 주문 확정에 성공하면 결제액과 결제 결과를 남기고 주문을 결제 완료 상태인 `CONFIRMED`로 변경한다.")
    @Nested
    class ConfirmInFlow {

        @DisplayName("[상태 전이] DRAFT로 생성한 주문을 확정하면 응답은 CONFIRMED이고 결제액 7,000과 결제 시점을 담는다.")
        @Test
        void returnsConfirmedOrder() throws Exception {
            // act
            Flow flow = runFlow();

            // assert
            JsonNode created = data(flow.created());
            JsonNode confirmed = data(flow.confirmed());
            assertAll(
                () -> assertThat(created.path("status").asText()).isEqualTo("DRAFT"),
                () -> assertThat(confirmed.path("id").asLong()).isEqualTo(flow.orderId()),
                () -> assertThat(confirmed.path("status").asText()).isEqualTo("CONFIRMED"),
                () -> assertThat(confirmed.path("payment").path("amount").asLong()).isEqualTo(7_000L),
                () -> assertThat(confirmed.path("payment").path("paidAt").asText()).isNotBlank()
            );
        }
    }

    @DisplayName("[R-ORDER-14] 고객은 주문에서 품목·수량·금액·상태·결제액을 확인할 수 있다.")
    @Nested
    class ReadOrderAfterFlow {

        @DisplayName("[상태 전이] 흐름 뒤 내 주문 상세에는 CONFIRMED, 품목 A 2개·B 1개와 단가·금액, 합계와 결제액 7,000이 있고, 내 주문 목록의 결제액도 7,000이다.")
        @Test
        void showsConfirmedOrder() throws Exception {
            // arrange
            Flow flow = runFlow();

            // act
            MvcResult detail = mockMvc.perform(get(ORDER, flow.orderId()).with(customer(flow.setup().customer())))
                .andExpect(success(HttpStatus.OK))
                .andReturn();
            MvcResult list = mockMvc.perform(get(ORDERS).with(customer(flow.setup().customer())))
                .andExpect(success(HttpStatus.OK))
                .andReturn();

            // assert
            JsonNode order = data(detail);
            JsonNode itemA = findByProductId(order.path("items"), flow.setup().a().getId());
            JsonNode itemB = findByProductId(order.path("items"), flow.setup().b().getId());
            JsonNode summary = findById(data(list).path("content"), flow.orderId());
            assertAll(
                () -> assertThat(order.path("status").asText()).isEqualTo("CONFIRMED"),
                () -> assertThat(itemA.path("quantity").asInt()).isEqualTo(2),
                () -> assertThat(itemA.path("unitPrice").asLong()).isEqualTo(2_000L),
                () -> assertThat(itemA.path("amount").asLong()).isEqualTo(4_000L),
                () -> assertThat(itemB.path("quantity").asInt()).isEqualTo(1),
                () -> assertThat(itemB.path("unitPrice").asLong()).isEqualTo(3_000L),
                () -> assertThat(itemB.path("amount").asLong()).isEqualTo(3_000L),
                () -> assertThat(order.path("totalAmount").asLong()).isEqualTo(7_000L),
                () -> assertThat(order.path("payment").path("amount").asLong()).isEqualTo(7_000L),
                () -> assertThat(summary.path("status").asText()).isEqualTo("CONFIRMED"),
                () -> assertThat(summary.path("totalAmount").asLong()).isEqualTo(7_000L),
                () -> assertThat(summary.path("paymentAmount").asLong()).isEqualTo(7_000L)
            );
        }
    }

    private Setup setup() {
        User customer = fixture.user();
        Brand brand = fixture.brand("Nike");
        Product a = fixture.product(brand, "A", 2_000L, 5);
        Product b = fixture.product(brand, "B", 3_000L, 3);
        return new Setup(customer, a, b);
    }

    private Flow runFlow() throws Exception {
        Setup setup = setup();
        MvcResult charged = charge(setup.customer());
        MvcResult created = createOrder(setup);
        long orderId = data(created).path("id").asLong();
        MvcResult confirmed = confirm(setup.customer(), orderId);
        return new Flow(setup, charged, created, orderId, confirmed);
    }

    private MvcResult charge(User customer) throws Exception {
        return mockMvc.perform(post(CHARGE)
                .with(customer(customer)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amount\":10000}"))
            .andExpect(success(HttpStatus.OK))
            .andReturn();
    }

    private MvcResult createOrder(Setup setup) throws Exception {
        return mockMvc.perform(post(ORDERS)
                .with(customer(setup.customer())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(orderBody(setup)))
            .andExpect(success(HttpStatus.CREATED))
            .andReturn();
    }

    private MvcResult confirm(User customer, long orderId) throws Exception {
        return mockMvc.perform(post(CONFIRM, orderId).with(customer(customer)).with(csrf()))
            .andExpect(success(HttpStatus.OK))
            .andReturn();
    }

    private long balanceOf(User customer) throws Exception {
        MvcResult result = mockMvc.perform(get(POINTS).with(customer(customer)))
            .andExpect(success(HttpStatus.OK))
            .andReturn();
        return data(result).path("balance").asLong(-1);
    }

    private int stockOf(Product product) throws Exception {
        MvcResult result = mockMvc.perform(get(ADMIN_PRODUCT, product.getId()).with(admin()))
            .andExpect(success(HttpStatus.OK))
            .andReturn();
        return data(result).path("stock").asInt(-1);
    }

    private static String orderBody(Setup setup) {
        return "{\"items\":[{\"productId\":%d,\"quantity\":2},{\"productId\":%d,\"quantity\":1}]}"
            .formatted(setup.a().getId(), setup.b().getId());
    }

    record Setup(User customer, Product a, Product b) {
    }

    record Flow(Setup setup, MvcResult charged, MvcResult created, long orderId, MvcResult confirmed) {
    }
}
