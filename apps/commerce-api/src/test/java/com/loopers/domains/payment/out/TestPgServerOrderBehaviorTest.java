package com.loopers.domains.payment.out;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.common.domain.Money;
import com.loopers.domains.payment.app.port.PaymentCreateCommand;
import com.loopers.domains.payment.app.port.PaymentCreateResult;
import com.loopers.domains.payment.out.TestPgServer.CreateBehavior;
import com.loopers.domains.payment.out.TestPgServer.StoredTransaction;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class TestPgServerOrderBehaviorTest {
    private static final String USER_ID = "135135";
    private static final String CALLBACK_URL = "http://localhost:8080/api/v1/payments/callback";
    private static final String LOST_ORDER_ID = "LP-ORD-0000000101";
    private static final String NORMAL_ORDER_ID = "LP-ORD-0000000102";
    private static final long AMOUNT = 5_000;
    // 응답 유실은 서버 지연이 read timeout보다 길어야 재현된다.
    private static final Duration SERVER_DELAY = Duration.ofMillis(1_500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(400);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private TestPgServer server;
    private TestPgPaymentGateway gateway;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestPgServer(USER_ID, SERVER_DELAY);
        gateway = new TestPgPaymentGateway(
            new TestPgProperties(server.baseUrl(), USER_ID, CALLBACK_URL, Duration.ofMillis(300), READ_TIMEOUT), objectMapper);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        server.close();
    }

    @Test
    @DisplayName("응답 유실을 지정한 주문은 서버에 SUCCESS 거래 1건이 저장되고 클라이언트는 결과 모름(Unknown)을 받는다")
    void lostResponseOrderIsStoredAsSuccessAndClientGetsUnknown() {
        server.loseResponseAfterSuccess(LOST_ORDER_ID);

        PaymentCreateResult result = gateway.create(command(LOST_ORDER_ID));

        assertThat(result).isInstanceOf(PaymentCreateResult.Unknown.class);
        List<StoredTransaction> stored = transactionsOf(LOST_ORDER_ID);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).status()).isEqualTo("SUCCESS");
    }

    @Test
    @DisplayName("같은 서버에서 응답 유실을 지정하지 않은 주문은 기본 동작대로 Accepted를 받고 PENDING으로 저장된다")
    void unmarkedOrderFollowsDefaultBehavior() {
        server.loseResponseAfterSuccess(LOST_ORDER_ID);

        PaymentCreateResult lost = gateway.create(command(LOST_ORDER_ID));
        PaymentCreateResult normal = gateway.create(command(NORMAL_ORDER_ID));

        assertThat(lost).isInstanceOf(PaymentCreateResult.Unknown.class);
        assertThat(normal).isInstanceOf(PaymentCreateResult.Accepted.class);
        List<StoredTransaction> stored = transactionsOf(NORMAL_ORDER_ID);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).status()).isEqualTo("PENDING");
    }

    @ParameterizedTest(name = "전역 동작 {0}")
    @EnumSource(value = CreateBehavior.class, names = {"ACCEPT_PENDING", "REJECT_BEFORE_CREATE", "ERROR_500_WITHOUT_CREATE"})
    @DisplayName("같은 주문으로 create가 두 번 오면 오류 응답을 돌려줘도 호출 2회와 중복 주문으로 기록된다")
    void secondCreateOfSameOrderIsRecorded(CreateBehavior behavior) {
        server.createBehavior(behavior);

        gateway.create(command(LOST_ORDER_ID));
        gateway.create(command(LOST_ORDER_ID));
        gateway.create(command(NORMAL_ORDER_ID));

        assertThat(server.createCalls(LOST_ORDER_ID)).isEqualTo(2);
        assertThat(server.createCalls(NORMAL_ORDER_ID)).isEqualTo(1);
        assertThat(server.duplicateCreateOrderIds()).containsExactly(LOST_ORDER_ID);
    }

    @Test
    @DisplayName("응답 유실 지정을 해제하면 같은 주문의 다음 create는 기본 동작대로 Accepted를 받는다")
    void clearedOrderReturnsToDefaultBehavior() {
        server.loseResponseAfterSuccess(LOST_ORDER_ID);
        PaymentCreateResult beforeClear = gateway.create(command(LOST_ORDER_ID));

        server.clearOrderBehavior(LOST_ORDER_ID);
        PaymentCreateResult afterClear = gateway.create(command(LOST_ORDER_ID));

        assertThat(beforeClear).isInstanceOf(PaymentCreateResult.Unknown.class);
        assertThat(afterClear).isInstanceOf(PaymentCreateResult.Accepted.class);
    }

    private List<StoredTransaction> transactionsOf(String orderId) {
        return server.transactions().stream()
            .filter(transaction -> transaction.orderId().equals(orderId))
            .toList();
    }

    private static PaymentCreateCommand command(String orderId) {
        return new PaymentCreateCommand(orderId, "SAMSUNG", "1234-5678-9814-1451", new Money(AMOUNT));
    }
}
