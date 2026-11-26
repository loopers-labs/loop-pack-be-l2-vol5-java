package com.loopers.interfaces.api.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.pg.PgFixture;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class CheckoutIncidentTest {

    private static final String ORDERS = "/api/v1/orders";
    private static final Long USER = 1L;
    private static final int STOCK = 25;
    private static final PgFixture PG = PgFixture.shared();
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.OrderResponse>> ORDER =
        new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<ApiResponse<OrderV1Dto.ReconcileResponse>> RECONCILE =
        new ParameterizedTypeReference<>() {};

    private final TestRestTemplate testRestTemplate;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final ProductService productService;
    private final PaymentRepository paymentRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    private Long coat;

    @Autowired
    CheckoutIncidentTest(TestRestTemplate testRestTemplate, BrandFacade brandFacade, ProductFacade productFacade,
                         ProductService productService, PaymentRepository paymentRepository,
                         JdbcTemplate jdbcTemplate, DatabaseCleanUp databaseCleanUp) {
        this.testRestTemplate = testRestTemplate;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.productService = productService;
        this.paymentRepository = paymentRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        PG.reset();
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(STOCK));
    }

    @AfterEach
    void tearDown() {
        PG.keepCreateResponses();
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("PG 는 성공했는데 응답이 timeout 되거나 끊겨 잃은 세 주문은 UNKNOWN 으로 남고, 같은 세 주문을 reconcile 하면 PG 생성 호출을 더 보내지 않고 확정된다")
    void lostResponsesStayUnknownUntilTheSameOrdersAreReconciled(CapturedOutput output) {
        assertThat(PG.willDropNextCreateResponse()).isFalse();
        List<Long> baseline = placeOrders(10);
        baseline.forEach(this::confirm);
        succeedAtPg(baseline);
        baseline.forEach(this::reconcile);

        assertThat(withPaymentStatus(baseline, PaymentStatus.SUCCESS)).hasSize(10);
        assertThat(withPaymentStatus(baseline, PaymentStatus.UNKNOWN)).isEmpty();
        assertThat(confirmedOrders(baseline)).hasSize(10);
        assertThat(pgSuccessKeys(baseline)).hasSize(10);
        assertThat(PG.transactionCount()).isEqualTo(10);
        assertThat(PG.createCount()).isEqualTo(10);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 10));

        int faultStart = output.getOut().length();
        List<Long> fault = placeOrders(10);
        List<Long> timedOut = List.of(fault.get(2), fault.get(8));
        List<Long> disconnected = List.of(fault.get(5));
        List<Long> lost = fault.stream()
            .filter(orderId -> timedOut.contains(orderId) || disconnected.contains(orderId)).toList();
        List<Long> answered = fault.stream().filter(orderId -> !lost.contains(orderId)).toList();
        try {
            for (Long orderId : fault) {
                if (timedOut.contains(orderId)) {
                    confirmWhilePgWithholdsTheResponse(orderId);
                } else if (disconnected.contains(orderId)) {
                    PG.dropNextCreateResponse();
                    confirm(orderId);
                } else {
                    confirm(orderId);
                }
            }
        } finally {
            PG.keepCreateResponses();
        }
        succeedAtPg(fault);
        answered.forEach(this::reconcile);

        List<String> faultKeys = pgSuccessKeys(fault);
        assertThat(withPaymentStatus(fault, PaymentStatus.SUCCESS)).containsExactlyElementsOf(answered);
        assertThat(withPaymentStatus(fault, PaymentStatus.UNKNOWN)).containsExactlyElementsOf(lost);
        assertThat(confirmedOrders(fault)).containsExactlyElementsOf(answered);
        assertThat(faultKeys).hasSize(10);
        assertThat(PG.transactionCount()).isEqualTo(20);
        assertThat(PG.createCount()).isEqualTo(20);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 20));

        String faultLog = output.getOut().substring(faultStart);
        assertThat(timedOut).allSatisfy(orderId -> assertThat(linesWith(faultLog, providerOrderIdOf(orderId)))
            .singleElement().asString().contains("cause=timeout"));
        assertThat(disconnected).allSatisfy(orderId -> assertThat(linesWith(faultLog, providerOrderIdOf(orderId)))
            .singleElement().asString().contains("cause=connection_lost"));
        assertThat(lost).allSatisfy(orderId ->
            assertThat(linesWith(faultLog, "orderId=" + orderId + ",", "result=unknown")).hasSize(1));
        assertThat(answered).allSatisfy(orderId -> {
            assertThat(linesWith(faultLog, providerOrderIdOf(orderId))).isEmpty();
            assertThat(linesWith(faultLog, "orderId=" + orderId + ",", "result=")).isEmpty();
        });
        assertThat(linesWith(faultLog, "error=")).isEmpty();
        assertThat(linesWith(faultLog, "from=UNKNOWN")).isEmpty();

        assertThat(PG.willDropNextCreateResponse()).isFalse();
        int recoveryStart = output.getOut().length();
        lost.forEach(this::reconcile);

        assertThat(withPaymentStatus(fault, PaymentStatus.SUCCESS)).containsExactlyElementsOf(fault);
        assertThat(withPaymentStatus(fault, PaymentStatus.UNKNOWN)).isEmpty();
        assertThat(confirmedOrders(fault)).containsExactlyElementsOf(fault);
        assertThat(pgSuccessKeys(fault)).containsExactlyElementsOf(faultKeys);
        assertThat(PG.transactionCount()).isEqualTo(20);
        assertThat(PG.createCount()).isEqualTo(20);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 20));

        fault.forEach(this::reconcile);

        assertThat(confirmedOrders(fault)).containsExactlyElementsOf(fault);
        assertThat(fault).allSatisfy(orderId -> assertThat(outboxRows(orderId)).isEqualTo(1));
        String recoveryLog = output.getOut().substring(recoveryStart);
        assertThat(lost).allSatisfy(orderId ->
            assertThat(linesWith(recoveryLog, "orderId=" + orderId + ",", "from="))
                .singleElement().asString().contains("source=reconcile", "from=UNKNOWN", "to=SUCCESS"));
        assertThat(linesWith(recoveryLog, "from=")).hasSize(3);
        assertThat(linesWith(recoveryLog, "error=")).isEmpty();
        assertThat(PG.createCount()).isEqualTo(20);
        assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(STOCK - 20));
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-USER-ID", String.valueOf(USER));
        return headers;
    }

    private List<Long> placeOrders(int count) {
        List<Long> orderIds = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Object body = Map.of("lines", List.of(Map.of("productId", coat, "quantity", 1)));
            orderIds.add(testRestTemplate.exchange(ORDERS, HttpMethod.POST, new HttpEntity<>(body, headers()), ORDER)
                .getBody().data().id());
        }
        return orderIds;
    }

    private void confirm(Long orderId) {
        testRestTemplate.exchange(ORDERS + "/" + orderId + "/confirm", HttpMethod.POST,
            new HttpEntity<>(Map.of("paymentAttemptKey", "attempt-" + orderId, "cardType", "TEST",
                "cardNo", "0000-0000-0000-0000"), headers()), Object.class);
    }

    private void confirmWhilePgWithholdsTheResponse(Long orderId) {
        PgFixture.HeldResponse held = PG.holdNextCreateResponse();
        try {
            confirm(orderId);
        } finally {
            held.release();
        }
    }

    private void reconcile(Long orderId) {
        testRestTemplate.exchange(ORDERS + "/" + orderId + "/payment/reconcile", HttpMethod.POST,
            new HttpEntity<>(null, headers()), RECONCILE);
    }

    private void succeedAtPg(List<Long> orderIds) {
        orderIds.forEach(orderId -> PG.transactionsOf(paymentOf(orderId).getProviderOrderId().value())
            .forEach(transaction -> PG.changeStatus(transaction.transactionKey(), PgFixture.Status.SUCCESS)));
    }

    private List<String> pgSuccessKeys(List<Long> orderIds) {
        return orderIds.stream()
            .flatMap(orderId -> PG.transactionsOf(paymentOf(orderId).getProviderOrderId().value()).stream())
            .filter(transaction -> transaction.status() == PgFixture.Status.SUCCESS)
            .map(PgFixture.Transaction::transactionKey)
            .toList();
    }

    private List<Long> withPaymentStatus(List<Long> orderIds, PaymentStatus status) {
        return orderIds.stream().filter(orderId -> paymentOf(orderId).getStatus() == status).toList();
    }

    private List<Long> confirmedOrders(List<Long> orderIds) {
        return orderIds.stream()
            .filter(orderId -> "CONFIRMED".equals(
                testRestTemplate.exchange(ORDERS + "/" + orderId, HttpMethod.GET, new HttpEntity<>(null, headers()), ORDER)
                    .getBody().data().status()))
            .toList();
    }

    private String providerOrderIdOf(Long orderId) {
        return "providerOrderId=" + paymentOf(orderId).getProviderOrderId().value();
    }

    private List<String> linesWith(String log, String... markers) {
        return log.lines()
            .filter(line -> Arrays.stream(markers).allMatch(line::contains))
            .toList();
    }

    private Payment paymentOf(Long orderId) {
        return paymentRepository.findByOrderId(orderId).orElseThrow();
    }

    private int outboxRows(Long orderId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from order_event_outbox where order_id = ?", Integer.class, orderId);
    }
}
