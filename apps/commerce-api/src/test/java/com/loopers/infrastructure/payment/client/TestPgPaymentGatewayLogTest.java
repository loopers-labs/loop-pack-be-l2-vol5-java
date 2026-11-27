package com.loopers.infrastructure.payment.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domain.payment.gateway.PaymentGatewayRequest;
import com.loopers.support.pg.TestPgGateways;
import com.loopers.support.pg.TestPgServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/** 사고 진단에 쓰는 PG 경계 로그(10주차 D-200)의 필드와 민감 정보 미포함을 확인함 */
class TestPgPaymentGatewayLogTest {

    private static final String ORDER_ID = "LP-ORD-0000000042";
    private static final long AMOUNT = 7_000L;
    private static final String CARD_TYPE = "SAMSUNG";
    private static final String CARD_NO = "1234-5678-9012-3456";
    private static final PaymentGatewayRequest REQUEST = new PaymentGatewayRequest(ORDER_ID, CARD_TYPE, CARD_NO, AMOUNT);

    private final Logger logger = (Logger) LoggerFactory.getLogger(TestPgPaymentGateway.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private TestPgServer pg;
    private TestPgPaymentGateway gateway;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        pg = TestPgServer.start();
        gateway = TestPgGateways.gateway(pg.baseUrl(), Duration.ofMillis(500), TestPgGateways.singleAttemptPolicy());
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        pg.close();
    }

    @DisplayName("생성이 수락되면 주문 · 금액 · 거래 키를 info 로 남긴다.")
    @Test
    void logsAcceptedCreate() {
        // act
        gateway.create(REQUEST);

        // assert
        String transactionKey = pg.transactionsOf(ORDER_ID).get(0).transactionKey();
        assertThat(messages(Level.INFO)).containsExactly(
            "PG 생성 결과. providerOrderId=" + ORDER_ID + " amount=" + AMOUNT + " result=ACCEPTED transactionKey=" + transactionKey);
    }

    @DisplayName("거래를 만든 뒤 응답을 잃으면 결과 미확인과 실제 예외 종류를 warn 으로 남긴다.")
    @Test
    void logsUnknownCreateWithCause() {
        // arrange
        pg.willDropResponseFor(Set.of(ORDER_ID));

        // act
        gateway.create(REQUEST);

        // assert
        List<String> warnings = messages(Level.WARN);
        assertAll(
            () -> assertThat(warnings).hasSize(1),
            () -> assertThat(warnings.get(0))
                .startsWith("PG 생성 결과. providerOrderId=" + ORDER_ID + " amount=" + AMOUNT + " result=UNKNOWN cause=")
                .doesNotEndWith("cause=")
        );
    }

    @DisplayName("계약과 다른 생성 응답이면 결과 미확인과 받은 상태 코드를 남긴다.")
    @Test
    void logsUnknownCreateWithStatus() {
        // arrange
        pg.willRespondToCreate(500, "{\"code\":\"INTERNAL\"}");

        // act
        gateway.create(REQUEST);

        // assert
        assertThat(messages(Level.WARN)).containsExactly(
            "PG 생성 결과. providerOrderId=" + ORDER_ID + " amount=" + AMOUNT + " result=UNKNOWN cause=status=500");
    }

    @DisplayName("주문 조회가 되면 거래 수와 거래별 키 · 상태 · 금액을 남긴다.")
    @Test
    void logsFoundLookup() {
        // arrange
        String transactionKey = pg.addTransaction(ORDER_ID, AMOUNT, "SUCCESS");

        // act
        gateway.findByProviderOrderId(ORDER_ID);

        // assert
        assertThat(messages(Level.INFO)).containsExactly(
            "PG 조회 결과. providerOrderId=" + ORDER_ID + " result=FOUND count=1 transactions=[" + transactionKey + ":SUCCESS:" + AMOUNT + "]");
    }

    @DisplayName("조회할 수 없으면 조회 불가와 원인을 warn 으로 남긴다.")
    @Test
    void logsUnavailableLookup() {
        // arrange
        pg.lookupWillRespond(500, "{}");

        // act
        gateway.findByProviderOrderId(ORDER_ID);

        // assert
        List<String> warnings = messages(Level.WARN);
        assertAll(
            () -> assertThat(warnings).hasSize(1),
            () -> assertThat(warnings.get(0)).startsWith("PG 조회 결과. providerOrderId=" + ORDER_ID + " result=UNAVAILABLE cause=")
        );
    }

    @DisplayName("어떤 결과에서도 카드 번호 · 카드 종류를 남기지 않는다.")
    @Test
    void neverLogsCardData() {
        // act
        gateway.create(REQUEST);
        pg.willDropResponseFor(Set.of(ORDER_ID));
        gateway.create(REQUEST);
        pg.willRespondToCreate(400, "{}");
        pg.stopDroppingResponses();
        gateway.create(REQUEST);
        gateway.findByProviderOrderId(ORDER_ID);

        // assert
        assertThat(appender.list).hasSizeGreaterThanOrEqualTo(4);
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
            .doesNotContain(CARD_NO)
            .doesNotContain(CARD_TYPE));
    }

    private List<String> messages(Level level) {
        return appender.list.stream()
            .filter(event -> event.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }
}
