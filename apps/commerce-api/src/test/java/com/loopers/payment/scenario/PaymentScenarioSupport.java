package com.loopers.payment.scenario;

import com.jayway.jsonpath.JsonPath;
import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.application.PaymentResultApplier;
import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.application.port.in.PaymentRecoveryUseCase;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.application.port.in.StockReservationUseCase;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.pg.LocalTestPgServer;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 장면 테스트 S-1~S-11 공통 바탕 (W5 plan.md 10장). 모든 장면이 같은 Spring 컨텍스트 하나를 쓰도록 설정·spy를 여기에만 둔다.
 *
 * <ul>
 *   <li>로컬 테스트 PG fixture가 실제 HTTP로 callback을 보내야 하므로 서버를 정해 둔 빈 포트로 띄운다. 고객 요청은 MockMvc로 보낸다.
 *   관리 포트(운영 8081)는 임의 포트로 둔다 — 같은 JVM에 캐시된 다른 DEFINED_PORT 컨텍스트(PaymentApiE2ETest)가 8081을 쥐고 있다.</li>
 *   <li>timeout은 짧게 줄인다: 생성 읽기 500ms, 조회 읽기 300ms. 회로 OPEN 대기는 2s (운영 10s) — S-10에서 회복을 기다리기 위해서다.</li>
 *   <li>스케줄러는 test 프로필에서 꺼져 있다. 정리·reconcile은 {@link PaymentRecoveryUseCase}를 "지금"을 넘겨 직접 부른다.</li>
 *   <li>spy는 평소에는 실제 동작을 그대로 하고, 장애를 넣는 장면(S-7·S-8)만 한 번씩 바꾼다. 각 테스트가 끝나면 Mockito가 되돌린다.</li>
 * </ul>
 * 재고 예 (plan.md 10장): 재고 5 · 수량 2 선점 → 가용 3. 성공 후 3, 확정 실패 후 5. 중복 실패로 7, UNKNOWN에서 5가 되면 버그.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
        "pg.local.create-read-timeout=500ms",
        "pg.local.lookup-read-timeout=300ms",
        "resilience4j.circuitbreaker.configs.pg.wait-duration-in-open-state=2s",
        "management.server.port=0",
    })
@AutoConfigureMockMvc
abstract class PaymentScenarioSupport {

    /** 장면 클래스 모두가 같은 컨텍스트(같은 base-url)를 쓰므로 fixture도 하나다. JVM이 끝날 때 멈춘다. */
    protected static final LocalTestPgServer PG = LocalTestPgServer.start();
    private static final int PORT = freePort();

    protected static final String USER_HEADER = "X-USER-ID";
    protected static final String KEY_HEADER = "Idempotency-Key";
    protected static final String CARD_NO = "1234-5678-9814-1451";
    protected static final String CARD_BODY = "{\"cardType\": \"SAMSUNG\", \"cardNo\": \"" + CARD_NO + "\"}";
    protected static final Duration WAIT = Duration.ofSeconds(5);
    protected static final String CREATE_CIRCUIT = "pgCreate";
    protected static final String LOOKUP_CIRCUIT = "pgLookup";

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("pg.local.base-url", PG::baseUrl);
        registry.add("pg.local.callback-url", () -> "http://127.0.0.1:" + PORT + "/api/v1/payments/callback");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected CircuitBreakerRegistry circuitBreakerRegistry;

    @Autowired
    protected PaymentRecoveryUseCase paymentRecoveryUseCase;

    /** S-8: commit 뒤 발송 이벤트를 잃은 상황을 만든다. */
    @MockitoSpyBean
    protected PaymentDispatchUseCase paymentDispatchUseCase;

    /** S-8: PG 호출 뒤 결과를 기록하기 전에 프로세스가 죽은 상황을 만든다. */
    @MockitoSpyBean
    protected PaymentResultApplier paymentResultApplier;

    /** S-7: 결과 반영 중 내부 저장 실패를 넣는다. */
    @MockitoSpyBean
    protected StockReservationUseCase stockReservationUseCase;

    @Autowired
    protected UserJpaRepository userJpaRepository;

    @Autowired
    protected BrandJpaRepository brandJpaRepository;

    @Autowired
    protected ProductJpaRepository productJpaRepository;

    @Autowired
    protected OrderJpaRepository orderJpaRepository;

    @Autowired
    protected PaymentJpaRepository paymentJpaRepository;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    protected UserModel user;
    protected UserModel other;
    protected BrandModel nike;
    protected ProductModel airMax;

    @BeforeEach
    void setUpScenario() {
        PG.reset();
        circuitBreakerRegistry.circuitBreaker(CREATE_CIRCUIT).reset();
        circuitBreakerRegistry.circuitBreaker(LOOKUP_CIRCUIT).reset();
        user = userJpaRepository.save(new UserModel("고객"));
        other = userJpaRepository.save(new UserModel("다른 고객"));
        nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 5));
    }

    @AfterEach
    void tearDownScenario() {
        databaseCleanUp.truncateAllTables();
    }

    // ---- 요청 ----

    /** 에어맥스 quantity개를 user로 주문한다 (DRAFT). */
    protected Long createOrder(int quantity) throws Exception {
        return createOrder(user, airMax, quantity);
    }

    protected Long createOrder(UserModel buyer, ProductModel product, int quantity) throws Exception {
        String response = mockMvc.perform(post("/api/v1/orders").header(USER_HEADER, buyer.getId())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\": [{\"productId\": " + product.getId() + ", \"quantity\": " + quantity + "}]}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return JsonPath.parse(response).read("$.data.id", Long.class);
    }

    protected ResultActions confirm(Long orderId, UserModel requester, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderId}/confirm", orderId)
            .header(USER_HEADER, requester.getId())
            .header(KEY_HEADER, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CARD_BODY));
    }

    protected ResultActions reconcile(Long orderId) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/{orderId}/payment/reconcile", orderId).header(USER_HEADER, user.getId()));
    }

    protected ResultActions getOrder(Long orderId, UserModel requester) throws Exception {
        return mockMvc.perform(get("/api/v1/orders/{orderId}", orderId).header(USER_HEADER, requester.getId()));
    }

    protected ResultActions getPayment(Long orderId) throws Exception {
        return mockMvc.perform(get("/api/v1/orders/{orderId}/payment", orderId).header(USER_HEADER, user.getId()));
    }

    /** 주문을 만들고 결제를 요청한 뒤, 비동기 발송이 기대 상태에 닿을 때까지 기다린다. */
    protected Long confirmAndAwait(int quantity, String key, PaymentStatus expected) throws Exception {
        Long orderId = createOrder(quantity);
        confirm(orderId, user, key);
        awaitPayment(orderId, expected);
        return orderId;
    }

    protected void awaitPayment(Long orderId, PaymentStatus expected) {
        await().atMost(WAIT).until(() -> paymentJpaRepository.findByOrderId(orderId)
            .map(Payment::getStatus)
            .filter(status -> status == expected)
            .isPresent());
    }

    // ---- 관찰 ----

    protected Payment paymentOf(Long orderId) {
        return paymentJpaRepository.findByOrderId(orderId).orElseThrow();
    }

    protected OrderModel orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    protected OrderStatus orderStatusOf(Long orderId) {
        return orderOf(orderId).getStatus();
    }

    protected int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    protected List<String> reservationsOf(Long orderId) {
        return jdbcTemplate.queryForList("SELECT status FROM stock_reservations WHERE order_id = ?", String.class, orderId);
    }

    protected String outboxStatusOf(Long orderId) {
        return jdbcTemplate.queryForObject(
            "SELECT o.status FROM payment_outbox o JOIN payments p ON p.id = o.payment_id WHERE p.order_id = ?", String.class, orderId);
    }

    protected CircuitBreaker circuit(String name) {
        return circuitBreakerRegistry.circuitBreaker(name);
    }
}
