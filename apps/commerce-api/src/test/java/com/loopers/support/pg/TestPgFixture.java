package com.loopers.support.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.order.ConfirmCommand;
import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.infrastructure.payment.TestPgPaymentGateway;
import com.loopers.infrastructure.payment.TestPgProperties;
import com.loopers.infrastructure.payment.TestPgServer;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;

// 테스트마다 TestPgServer를 새로 띄우고, 실제 HTTP 어댑터(TestPgPaymentGateway)를 그 주소로 만들어 위임한다.
// 앱 컨텍스트는 테스트 사이에 재사용되므로 서버 주소만 테스트 단위로 바꿔 끼우고, start/close로 수명을 테스트에 묶는다.
// PaymentGateway 빈으로 등록하면 운영 레지스트리가 providerId 중복으로 시작에 실패하므로, 위임 게이트웨이는 빈이 아닌 내부 객체로 둔다.
public class TestPgFixture {
    public static final String PROVIDER_ID = "test-pg";
    public static final String CARD_TYPE = "SAMSUNG";
    public static final String CARD_NO = "1234-5678-9814-1451";
    private static final String USER_ID = "135135";
    // 응답 유실 장면은 서버 지연(DELAY)이 read timeout보다 길어야 재현된다. 운영 설정값과는 무관한 테스트 값이다.
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);
    private static final Duration SERVER_DELAY = Duration.ofMillis(1500);

    private final ObjectMapper objectMapper;
    private volatile TestPgServer server;
    private volatile PaymentGateway delegate;
    private volatile boolean crashBeforeSend;
    private volatile String failingLookupOrder;
    private final PaymentGateway gateway = new DelegatingGateway();

    public TestPgFixture(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public static ConfirmCommand command() {
        return command(UUID.randomUUID().toString());
    }

    public static ConfirmCommand command(String attemptKey) {
        return new ConfirmCommand(attemptKey, CARD_TYPE, CARD_NO);
    }

    public TestPgServer start() {
        if (server != null) {
            throw new IllegalStateException("이전 테스트의 PG 서버가 닫히지 않았습니다.");
        }
        try {
            server = new TestPgServer(USER_ID, SERVER_DELAY);
        } catch (IOException e) {
            throw new IllegalStateException("테스트 PG 서버를 시작하지 못했습니다.", e);
        }
        delegate = newClient();
        crashBeforeSend = false;
        failingLookupOrder = null;
        return server;
    }

    public TestPgServer server() {
        if (server == null) {
            throw new IllegalStateException("테스트 PG 서버가 시작되지 않았습니다.");
        }
        return server;
    }

    // 같은 서버를 가리키는 새 HTTP 클라이언트(어댑터). 재시작 장면에서 서비스와 함께 다시 만든다.
    public PaymentGateway newClient() {
        URI baseUrl = server().baseUrl();
        return new TestPgPaymentGateway(new TestPgProperties(baseUrl, USER_ID, "http://127.0.0.1/api/v1/payments/callback",
            Duration.ofSeconds(1), READ_TIMEOUT), objectMapper);
    }

    // 트랜잭션 A commit 뒤 create를 보내기 전에 프로세스가 멈춘 상황을 흉내 낸다. 서버에는 아무 요청도 가지 않는다.
    public void crashBeforeSend() {
        crashBeforeSend = true;
    }

    public void failLookupFor(String providerOrderId) {
        failingLookupOrder = providerOrderId;
    }

    public void close() throws InterruptedException {
        TestPgServer current = server;
        server = null;
        delegate = null;
        crashBeforeSend = false;
        failingLookupOrder = null;
        if (current != null) {
            current.close();
        }
    }

    PaymentGateway gateway() {
        return gateway;
    }

    private PaymentGateway client() {
        PaymentGateway current = delegate;
        if (current == null) {
            throw new IllegalStateException("테스트 PG 서버가 시작되지 않았습니다.");
        }
        return current;
    }

    private final class DelegatingGateway implements PaymentGateway {
        @Override
        public String providerId() {
            return PROVIDER_ID;
        }

        @Override
        public PaymentCreateResult create(PaymentCreateCommand command) {
            if (crashBeforeSend) {
                throw new IllegalStateException("주입된 발송 전 중단");
            }
            return client().create(command);
        }

        @Override
        public PaymentTransactionsLookup verifyOrder(String providerOrderId, String storedTransactionKey) {
            if (providerOrderId.equals(failingLookupOrder)) {
                throw new IllegalStateException("주입된 주문별 조회 예외");
            }
            return client().verifyOrder(providerOrderId, storedTransactionKey);
        }
    }
}
