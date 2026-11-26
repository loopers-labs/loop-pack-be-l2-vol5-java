package com.loopers.domains.order.in;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domains.brand.app.BrandApplicationService;
import com.loopers.domains.order.app.OrderApplicationService;
import com.loopers.domains.payment.out.TestPgPaymentGateway;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer.LookupBehavior;
import com.loopers.domains.payment.out.TestPgServer;
import com.loopers.domains.product.app.ProductApplicationService;
import com.loopers.domains.user.out.UserJpaEntity;
import com.loopers.domains.user.out.UserJpaRepository;
import com.loopers.support.pg.MutablePaymentClock;
import com.loopers.support.pg.TestPgFixture;
import com.loopers.utils.DatabaseCleanUp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 3-2에서 경계 로그의 레벨·문구가 바뀌어도 깨지지 않도록 이벤트는 logger 이름과 메시지 일부로 고르고,
// 두 logger를 DEBUG로 열어 레벨과 무관하게 받는다.
@SpringBootTest
@AutoConfigureMockMvc
@Import(PaymentLookupScheduleIntegrationTest.TimeConfig.class)
class PaymentLogContextIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final List<String> BUSINESS_KEYS = List.of("orderId", "attemptKey", "providerOrderId", "transactionKey");
    private static final String SERVICE_LOGGER = OrderApplicationService.class.getName();
    private static final String ADAPTER_LOGGER = TestPgPaymentGateway.class.getName();

    @Autowired private MockMvc mvc;
    @Autowired private BrandApplicationService brands;
    @Autowired private ProductApplicationService products;
    @Autowired private OrderApplicationService orders;
    @Autowired private MutablePaymentClock clock;
    @Autowired private TestPgFixture pg;
    @Autowired private UserJpaRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;
    private TestPgServer server;
    private long productId;
    private long orderId;
    private SnapshotAppender logs;
    private Level serviceLevel;
    private Level adapterLevel;

    @BeforeEach
    void prepare() {
        MDC.clear();
        clock.set(NOW);
        server = pg.start();
        users.save(new UserJpaEntity(1));
        long brand = brands.create("브랜드").id();
        productId = products.create(brand, "상품", 1000, 5).id();
        orderId = newOrder();
        logs = new SnapshotAppender();
        logs.start();
        serviceLevel = logger(SERVICE_LOGGER).getLevel();
        adapterLevel = logger(ADAPTER_LOGGER).getLevel();
        logger(SERVICE_LOGGER).setLevel(Level.DEBUG);
        logger(ADAPTER_LOGGER).setLevel(Level.DEBUG);
        logger(SERVICE_LOGGER).addAppender(logs);
        logger(ADAPTER_LOGGER).addAppender(logs);
    }

    @AfterEach
    void clean() throws Exception {
        logger(SERVICE_LOGGER).detachAppender(logs);
        logger(ADAPTER_LOGGER).detachAppender(logs);
        logger(SERVICE_LOGGER).setLevel(serviceLevel);
        logger(ADAPTER_LOGGER).setLevel(adapterLevel);
        MDC.clear();
        pg.close();
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("응답이 유실된 confirm의 어댑터 결과 모름 로그에 orderId·attemptKey·providerOrderId가 붙고 끝나면 호출 스레드 MDC에서 사라진다")
    void adapterLogOfLostResponseCarriesOrderKeys() {
        String attemptKey = UUID.randomUUID().toString();
        pg.loseResponseAfterSuccess(providerOrderId(orderId));

        orders.confirm(1, orderId, TestPgFixture.command(attemptKey));

        ILoggingEvent event = single(ADAPTER_LOGGER, "알 수 없");
        assertThat(event.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("attemptKey", attemptKey)
            .containsEntry("providerOrderId", providerOrderId(orderId));
        assertBusinessKeysCleared();
        assertNoCardNumber();
    }

    @Test
    @DisplayName("정상 확정 confirm의 생성 결과 로그에 orderId·attemptKey·providerOrderId가 붙고 끝나면 호출 스레드 MDC에서 사라진다")
    void createResultLogOfConfirmedOrderCarriesOrderKeys() {
        String attemptKey = UUID.randomUUID().toString();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);

        orders.confirm(1, orderId, TestPgFixture.command(attemptKey));

        ILoggingEvent event = single(SERVICE_LOGGER, "결제 생성 시도 결과");
        assertThat(event.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("attemptKey", attemptKey)
            .containsEntry("providerOrderId", providerOrderId(orderId));
        assertBusinessKeysCleared();
        assertNoCardNumber();
    }

    @Test
    @DisplayName("아는 주문의 callback은 결제 기록을 찾은 뒤의 조회 실패 로그에 orderId·attemptKey·providerOrderId가 붙고 끝나면 사라진다")
    void callbackOfKnownOrderCarriesOrderKeysAfterLookup() throws Exception {
        String attemptKey = UUID.randomUUID().toString();
        orders.confirm(1, orderId, TestPgFixture.command(attemptKey));
        server.lookupBehavior(LookupBehavior.ERROR_500);
        logs.list.clear();

        callback(providerOrderId(orderId));

        List<ILoggingEvent> failures = events(ADAPTER_LOGGER, "조회에 실패");
        assertThat(failures).isNotEmpty();
        assertThat(failures).allSatisfy(event -> assertThat(event.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("attemptKey", attemptKey)
            .containsEntry("providerOrderId", providerOrderId(orderId)));
        assertBusinessKeysCleared();
        assertNoCardNumber();
    }

    @Test
    @DisplayName("모르는 주문의 callback 경고 로그에는 providerOrderId만 붙고 orderId·attemptKey는 없으며 끝나면 사라진다")
    void callbackOfUnknownOrderCarriesOnlyProviderOrderId() throws Exception {
        String unknownProviderOrderId = "LP-ORD-9999999999";

        callback(unknownProviderOrderId);

        ILoggingEvent event = single(SERVICE_LOGGER, "알 수 없는 주문");
        assertThat(event.getMDCPropertyMap())
            .containsEntry("providerOrderId", unknownProviderOrderId)
            .doesNotContainKeys("orderId", "attemptKey", "transactionKey");
        assertBusinessKeysCleared();
    }

    @Test
    @DisplayName("자동 재조회는 주문마다 열쇠를 바꿔 각 조회 실패 로그에 그 주문의 orderId·providerOrderId가 붙고 끝나면 사라진다")
    void dueLookupCarriesEachOrderKeys() {
        long otherOrderId = newOrder();
        orders.confirm(1, orderId, TestPgFixture.command());
        orders.confirm(1, otherOrderId, TestPgFixture.command());
        server.lookupBehavior(LookupBehavior.ERROR_500);
        clock.advance(Duration.ofSeconds(2));
        logs.list.clear();

        orders.reconcileDuePayments();

        List<ILoggingEvent> failures = events(ADAPTER_LOGGER, "조회에 실패");
        Map<String, Set<String>> providerOrderIdsByOrder = failures.stream()
            .collect(Collectors.groupingBy(event -> String.valueOf(event.getMDCPropertyMap().get("orderId")),
                Collectors.mapping(event -> String.valueOf(event.getMDCPropertyMap().get("providerOrderId")),
                    Collectors.toSet())));
        assertThat(providerOrderIdsByOrder).containsExactlyInAnyOrderEntriesOf(Map.of(
            String.valueOf(orderId), Set.of(providerOrderId(orderId)),
            String.valueOf(otherOrderId), Set.of(providerOrderId(otherOrderId))));
        assertBusinessKeysCleared();
        assertNoCardNumber();
    }

    @Test
    @DisplayName("만료는 주문마다 열쇠를 바꿔 각 만료 로그에 그 주문의 orderId가 붙고 끝나면 사라진다")
    void expiryCarriesEachOrderId() {
        long otherOrderId = newOrder();
        orders.confirm(1, orderId, TestPgFixture.command());
        orders.confirm(1, otherOrderId, TestPgFixture.command());
        clock.set(NOW.plus(Duration.ofMinutes(2)));
        logs.list.clear();

        orders.expireOverduePayments();

        List<ILoggingEvent> expired = events(SERVICE_LOGGER, "결제 만료로 종결");
        assertThat(expired).hasSize(2);
        assertThat(expired).allSatisfy(event -> assertThat(event.getFormattedMessage())
            .contains("orderId=" + event.getMDCPropertyMap().get("orderId")));
        assertThat(expired).extracting(event -> event.getMDCPropertyMap().get("orderId"))
            .containsExactlyInAnyOrder(String.valueOf(orderId), String.valueOf(otherOrderId));
        assertBusinessKeysCleared();
    }

    @Test
    @DisplayName("저장 키 없이 조회된 단일 거래가 금액이 다르면 판정 WARN 로그에 그 거래의 transactionKey가 붙지 않는다")
    void mismatchedTransactionKeyIsNotAttachedToReviewLog() {
        server.createBehavior(CreateBehavior.ERROR_500_AFTER_CREATE);
        orders.confirm(1, orderId, TestPgFixture.command());
        String foreignKey = server.transactions().get(0).transactionKey();
        server.replaceTransaction(foreignKey, providerOrderId(orderId), 999, "SUCCESS");
        logs.list.clear();

        orders.reconcilePayment(orderId);

        ILoggingEvent event = single(SERVICE_LOGGER, "result=Review");
        assertThat(event.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .doesNotContainKey("transactionKey");
        assertBusinessKeysCleared();
    }

    @Test
    @DisplayName("저장 키 없이 조회된 단일 거래가 주문·금액과 일치하면 Verified INFO 로그에 그 거래의 transactionKey가 붙는다")
    void matchedTransactionKeyIsAttachedToVerifiedLog() {
        server.createBehavior(CreateBehavior.ERROR_500_AFTER_CREATE);
        orders.confirm(1, orderId, TestPgFixture.command());
        String matchedKey = server.transactions().get(0).transactionKey();
        server.changeStatus(matchedKey, "SUCCESS");
        logs.list.clear();

        orders.reconcilePayment(orderId);

        ILoggingEvent event = single(SERVICE_LOGGER, "result=Verified");
        assertThat(event.getMDCPropertyMap())
            .containsEntry("orderId", String.valueOf(orderId))
            .containsEntry("transactionKey", matchedKey);
        assertBusinessKeysCleared();
    }

    private long newOrder() {
        return orders.create(1, List.of(new OrderApplicationService.ItemRequest(productId, 2))).id();
    }

    private void callback(String providerOrderId) throws Exception {
        mvc.perform(post("/api/v1/payments/callback").contentType(MediaType.APPLICATION_JSON)
                .content("{\"transactionKey\":\"TR-X\",\"orderId\":\"" + providerOrderId
                    + "\",\"amount\":1,\"status\":\"SUCCESS\"}"))
            .andExpect(status().isOk());
    }

    private List<ILoggingEvent> events(String loggerName, String fragment) {
        return logs.list.stream()
            .filter(event -> event.getLoggerName().equals(loggerName))
            .filter(event -> event.getFormattedMessage().contains(fragment))
            .toList();
    }

    private ILoggingEvent single(String loggerName, String fragment) {
        List<ILoggingEvent> found = events(loggerName, fragment);
        assertThat(found).hasSize(1);
        return found.get(0);
    }

    // 요청 단위 열쇠(traceId·spanId)는 이 테스트 대상이 아니므로 업무 열쇠만 본다.
    private static void assertBusinessKeysCleared() {
        for (String key : BUSINESS_KEYS) {
            assertThat(MDC.get(key)).as(key).isNull();
        }
    }

    private void assertNoCardNumber() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(TestPgFixture.CARD_NO);
            assertThat(event.getMDCPropertyMap().values()).noneMatch(value -> value.contains(TestPgFixture.CARD_NO));
        });
    }

    private static String providerOrderId(long id) {
        return String.format("LP-ORD-%010d", id);
    }

    private static Logger logger(String name) {
        return (Logger) LoggerFactory.getLogger(name);
    }

    // 이벤트의 MDC는 처음 읽을 때 현재 스레드에서 가져오므로, 기록 시점의 값을 남기려고 받는 즉시 고정한다.
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}
