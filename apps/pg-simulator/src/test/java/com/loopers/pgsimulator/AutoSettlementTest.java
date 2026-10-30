package com.loopers.pgsimulator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

@DisplayName("자동 처리는 지연 뒤 금액 규칙의 상태로 바꾸고 콜백을 1회만 보낸다.")
class AutoSettlementTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DELAY = Duration.ofMillis(300);
    private static final Duration DEADLINE = Duration.ofSeconds(5);
    /** 콜백 client 응답 timeout(1초)보다 길게 지켜봐 재전송이 없음을 확인한다. */
    private static final Duration NO_RESEND_WINDOW = Duration.ofMillis(1500);

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    private PgSimulator pg;
    private CallbackReceiverStub receiver;

    @AfterEach
    void tearDown() throws Exception {
        client.shutdownNow();
        if (receiver != null) {
            receiver.close();
        }
        if (pg != null) {
            pg.close();
            assertThat(pg.isTerminated()).isTrue();
        }
    }

    @DisplayName("6000원은 202 PENDING 으로 응답하고, 지연 전에는 PENDING·콜백 0이며 지연 뒤 SUCCESS 로 바뀌고 SUCCESS 콜백 1회를 보낸다.")
    @Test
    void settlesPendingToSuccess() throws IOException {
        start(Settlement.after(DELAY));

        HttpResponse<String> created = create("LP-ORD-0000000300", 6000);
        String key = readJson(created.body()).get("transactionKey").textValue();
        PaymentStatus beforeDelay = pg.transactions().findByKey(key).orElseThrow().status();
        int callbacksBeforeDelay = receiver.received().size();

        await().atMost(DEADLINE).until(() -> receiver.received().size() == 1);

        assertAll(
            () -> assertThat(created.statusCode()).isEqualTo(202),
            () -> assertThat(beforeDelay).isEqualTo(PaymentStatus.PENDING),
            () -> assertThat(callbacksBeforeDelay).isZero(),
            () -> assertThat(pg.transactions().findByKey(key).orElseThrow().status()).isEqualTo(PaymentStatus.SUCCESS),
            () -> assertThat(callbackStatus(0)).isEqualTo("SUCCESS")
        );
    }

    @DisplayName("7000원은 200 SUCCESS 로 응답하고, 지연 뒤 상태 변경 없이 SUCCESS 콜백 1회를 보낸다.")
    @Test
    void sendsCallbackForImmediateSuccess() throws IOException {
        start(Settlement.after(DELAY));

        HttpResponse<String> created = create("LP-ORD-0000000301", 7000);

        await().atMost(DEADLINE).until(() -> receiver.received().size() == 1);
        assertAll(
            () -> assertThat(created.statusCode()).isEqualTo(200),
            () -> assertThat(readJson(created.body()).get("status").textValue()).isEqualTo("SUCCESS"),
            () -> assertThat(pg.transactions().findAll()).singleElement()
                .extracting(TransactionSnapshot::status).isEqualTo(PaymentStatus.SUCCESS),
            () -> assertThat(callbackStatus(0)).isEqualTo("SUCCESS")
        );
    }

    @DisplayName("8000원은 202 PENDING 으로 응답하고, 지연 뒤 FAILED 로 바뀌고 FAILED 콜백 1회를 보낸다.")
    @Test
    void settlesPendingToFailed() throws IOException {
        start(Settlement.after(DELAY));

        HttpResponse<String> created = create("LP-ORD-0000000302", 8000);

        await().atMost(DEADLINE).until(() -> receiver.received().size() == 1);
        assertAll(
            () -> assertThat(created.statusCode()).isEqualTo(202),
            () -> assertThat(pg.transactions().findAll()).singleElement()
                .extracting(TransactionSnapshot::status).isEqualTo(PaymentStatus.FAILED),
            () -> assertThat(callbackStatus(0)).isEqualTo("FAILED")
        );
    }

    @DisplayName("콜백이 실패 응답을 받아도 다시 보내지 않는다.")
    @Test
    void doesNotResendAfterFailedCallback() throws IOException {
        start(Settlement.after(Duration.ZERO));
        receiver.replyWith(new CallbackReceiverStub.Reply(500, Map.of()));

        create("LP-ORD-0000000303", 6000);

        await().atMost(DEADLINE).until(() -> receiver.received().size() == 1);
        await().during(NO_RESEND_WINDOW).atMost(NO_RESEND_WINDOW.plusSeconds(1))
            .until(() -> receiver.received().size() == 1);
    }

    @DisplayName("생성 전 거절이면 거래·자동 처리·콜백이 없다.")
    @Test
    void doesNothingForRejection() throws IOException {
        receiver = CallbackReceiverStub.start();
        pg = PgSimulator.start(new PgSimulatorOptions(0, attempt -> CreateOutcome.REJECT_BEFORE_CREATE,
            Settlement.after(Duration.ZERO)));

        HttpResponse<String> created = create("LP-ORD-0000000304", 6000);

        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2))
            .until(() -> receiver.received().isEmpty() && pg.transactions().findAll().isEmpty());
        assertThat(created.statusCode()).isEqualTo(422);
    }

    @DisplayName("자동 처리를 끄면 지연이 지나도 상태가 PENDING 에 머물고 콜백을 보내지 않는다.")
    @Test
    void staysPendingWhenDisabled() throws IOException {
        start(Settlement.DISABLED);

        String key = readJson(create("LP-ORD-0000000305", 6000).body()).get("transactionKey").textValue();

        await().during(DELAY.multipliedBy(3)).atMost(Duration.ofSeconds(3))
            .until(() -> receiver.received().isEmpty()
                && pg.transactions().findByKey(key).orElseThrow().status() == PaymentStatus.PENDING);
    }

    @DisplayName("close 하면 예약된 자동 처리를 실행하지 않는다.")
    @Test
    void cancelsScheduledSettlementOnClose() throws IOException {
        start(Settlement.after(Duration.ofSeconds(1)));
        create("LP-ORD-0000000306", 6000);

        pg.close();

        await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(3)).until(() -> receiver.received().isEmpty());
        assertThat(pg.isTerminated()).isTrue();
    }

    private void start(Settlement settlement) throws IOException {
        receiver = CallbackReceiverStub.start();
        pg = PgSimulator.start(new PgSimulatorOptions(0, new AmountModuloOutcomePolicy(), settlement));
    }

    private HttpResponse<String> create(String orderId, long amount) {
        String body = "{\"orderId\":\"" + orderId + "\",\"cardType\":\"TEST\",\"cardNo\":\"0000-0000-0000-0000\","
            + "\"amount\":" + amount + ",\"callbackUrl\":\"" + receiver.callbackUrl() + "\"}";
        try {
            return client.send(HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments"))
                .header("X-USER-ID", "135135")
                .header("Content-Type", "application/json")
                .timeout(DEADLINE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String callbackStatus(int index) {
        return readJson(receiver.received().get(index).body()).get("status").textValue();
    }

    private static JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
