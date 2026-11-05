package com.loopers.outbox.adapter.in.management;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.outbox.application.port.in.OrderOutboxRelayUseCase;
import com.loopers.outbox.application.port.out.MessageSendPort;
import com.loopers.outbox.application.port.out.MessageSendPort.SentPosition;
import com.loopers.outbox.application.port.out.OrderOutboxPort;
import com.loopers.outbox.domain.OrderOutbox;
import com.loopers.outbox.domain.OrderOutboxStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * BLOCKED 재개 진입점 (docs/week7/plan.md D-3·4단계, C-9 재개 방법). 관리 포트의 POST /actuator/outbox/{eventId}.
 * 재개는 행을 PENDING(시도 0)으로 돌릴 뿐 보내지 않는다 — 보내는 것은 다음 relay 회차다(테스트는 relay를 직접 부른다).
 * 도메인의 resume 규칙을 그대로 거친다: BLOCKED만, 마지막 원인은 남긴다, 그 밖의 상태는 409이고 값이 그대로다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class OrderOutboxEndpointIntegrationTest {

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private OrderOutboxPort orderOutboxPort;

    @Autowired
    private OrderOutboxRelayUseCase relay;

    @MockitoBean
    private MessageSendPort messageSendPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 한 시간 전에 3번 실패해 BLOCKED가 된 행 */
    private void saveBlocked(String eventId, long orderId) {
        ZonedDateTime at = ZonedDateTime.now().minusHours(1);
        OrderOutbox outbox = OrderOutbox.pending(eventId, orderId, "OrderConfirmed", 1, "{\"orderId\":" + orderId + "}", at);
        for (int i = 1; i <= OrderOutbox.MAX_ATTEMPTS; i++) {
            outbox.startSending(at);
            outbox.recordFailure("broker down " + i, at, Duration.ZERO);
        }
        orderOutboxPort.save(outbox);
    }

    private HttpResponse<String> resume(String eventId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/outbox/" + eventId))
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private OrderOutbox row(String eventId) {
        return orderOutboxPort.findByEventId(eventId).orElseThrow();
    }

    @DisplayName("C-9 BLOCKED 행을 재개하면 200이고 PENDING · 시도 0이 된다. 마지막 원인은 남는다.")
    @Test
    void resumesBlockedRow() throws Exception {
        // arrange
        saveBlocked("e-1", 1L);

        // act
        HttpResponse<String> response = resume("e-1");

        // assert
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.get("eventId").asText()).isEqualTo("e-1");
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("attemptCount").asInt()).isZero();
        assertThat(row("e-1").getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
        assertThat(row("e-1").getAttemptCount()).isZero();
        assertThat(row("e-1").getLastError()).isEqualTo("broker down 3");
    }

    @DisplayName("C-9 재개한 행은 다음 relay 회차가 같은 eventId·payload로 보내고, ack를 받으면 PUBLISHED다.")
    @Test
    void resumedRowIsSentByNextRelayRound() throws Exception {
        // arrange
        saveBlocked("e-1", 1L);
        when(messageSendPort.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new SentPosition(1, 42L)));
        assertThat(relay.relayOnce().selected()).isZero(); // BLOCKED는 고르지 않는다

        // act
        resume("e-1");
        relay.relayOnce();

        // assert
        assertThat(row("e-1").getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(row("e-1").getPublishedOffset()).isEqualTo(42L);
    }

    @DisplayName("BLOCKED가 아닌 행(PUBLISHED)을 재개하면 409이고 값이 그대로다 — 사람이 eventId를 잘못 넣은 경우.")
    @Test
    void rejectsNonBlockedRow() throws Exception {
        // arrange: 바로 보낼 차례인 PENDING 행을 보내 PUBLISHED로 만든다
        orderOutboxPort.save(OrderOutbox.pending("e-2", 2L, "OrderConfirmed", 1, "{}", ZonedDateTime.now().minusMinutes(1)));
        when(messageSendPort.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(new SentPosition(0, 7L)));
        relay.relayOnce();

        // act
        HttpResponse<String> response = resume("e-2");

        // assert
        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(row("e-2").getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
        assertThat(row("e-2").getPublishedOffset()).isEqualTo(7L);
    }

    @DisplayName("없는 eventId를 재개하면 404다.")
    @Test
    void unknownEventIdIsNotFound() throws Exception {
        // act
        HttpResponse<String> response = resume("no-such-event");

        // assert
        assertThat(response.statusCode()).isEqualTo(404);
    }
}
