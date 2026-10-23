package com.loopers.infrastructure.payment.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalTestPgTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ORDER = "LP-ORD-0000000042";

    @Test
    void createsANewExternalTransactionForEveryPostEvenWithTheSameOrder() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            HttpResponse<String> first = post(client, pg, body(pg), "135135");
            HttpResponse<String> second = post(client, pg, body(pg), "135135");
            assertThat(first.statusCode()).isEqualTo(202);
            assertThat(second.statusCode()).isEqualTo(202);
            JsonNode transaction = JSON.readTree(first.body());
            assertThat(transaction.get("orderId").asText()).isEqualTo(ORDER);
            assertThat(transaction.get("amount").isIntegralNumber()).isTrue();
            assertThat(transaction.get("amount").longValue()).isEqualTo(7000);
            assertThat(transaction.get("status").asText()).isEqualTo("PENDING");
            assertThat(JSON.readTree(second.body()).get("transactionKey").asText())
                .isNotEqualTo(transaction.get("transactionKey").asText());
            assertThat(pg.createCalls()).isEqualTo(2);
            assertThat(pg.transactionCount()).isEqualTo(2);
        }
    }

    @Test
    void returnsTheSameTransactionThroughKeyAndOrderLookups() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            String key = JSON.readTree(post(client, pg, body(pg), "135135").body())
                .get("transactionKey").asText();
            pg.setStatus(key, "SUCCESS");
            HttpResponse<String> one = get(client, pg.baseUri().resolve("/api/v1/payments/" + key));
            HttpResponse<String> list = get(client, pg.baseUri().resolve("/api/v1/payments?orderId=" + ORDER));
            assertThat(one.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(one.body()).get("status").asText()).isEqualTo("SUCCESS");
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(list.body())).hasSize(1);
            assertThat(JSON.readTree(list.body()).get(0)).isEqualTo(JSON.readTree(one.body()));
            assertThat(pg.lookupCalls()).isEqualTo(2);
            assertThat(pg.createCalls()).isEqualTo(1);
        }
    }

    @Test
    void representsMissingTransactionAndEmptyOrderDifferently() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            assertThat(get(client, pg.baseUri().resolve("/api/v1/payments/unknown")).statusCode()).isEqualTo(404);
            HttpResponse<String> empty = get(client, pg.baseUri().resolve("/api/v1/payments?orderId=" + ORDER));
            assertThat(empty.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(empty.body())).isEmpty();
            assertThat(pg.transactionCount()).isZero();
        }
    }

    @Test
    void rejectsBeforeCreatingAnyExternalTransaction() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            pg.nextCreateMode(LocalTestPg.CreateMode.REJECT_BEFORE_CREATE);
            HttpResponse<String> response = post(client, pg, body(pg), "135135");
            assertThat(response.statusCode()).isEqualTo(422);
            JsonNode rejection = JSON.readTree(response.body());
            assertThat(rejection.get("code").asText()).isEqualTo("TEST_PG_REJECTED_BEFORE_CREATE");
            assertThat(rejection.get("orderId").asText()).isEqualTo(ORDER);
            assertThat(rejection.get("amount").longValue()).isEqualTo(7000);
            assertThat(rejection.get("transactionCreated").isBoolean()).isTrue();
            assertThat(rejection.get("transactionCreated").booleanValue()).isFalse();
            assertThat(pg.transactionCount()).isZero();
            assertThat(pg.createCalls()).isEqualTo(1);
        }
    }

    @Test
    void losesTheResponseOnlyAfterPersistingTheExternalTransaction() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            pg.nextCreateMode(LocalTestPg.CreateMode.LOSE_RESPONSE_AFTER_CREATE);
            assertThatThrownBy(() -> post(client, pg, body(pg), "135135"))
                .isInstanceOf(java.io.IOException.class);
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(pg.createCalls()).isEqualTo(1);
            HttpResponse<String> lookup = get(client, pg.baseUri().resolve("/api/v1/payments?orderId=" + ORDER));
            assertThat(JSON.readTree(lookup.body())).hasSize(1);
        }
    }

    @Test
    void canHoldAndReleaseTheResponseWithoutRandomDelay() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            pg.nextCreateMode(LocalTestPg.CreateMode.HOLD_RESPONSE_AFTER_CREATE);
            var response = client.sendAsync(postRequest(pg, body(pg), "135135"), HttpResponse.BodyHandlers.ofString());
            assertThat(pg.awaitCreate(Duration.ofSeconds(2))).isTrue();
            assertThat(pg.transactionCount()).isEqualTo(1);
            assertThat(response.isDone()).isFalse();
            pg.releaseResponses();
            assertThat(response.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
        }
    }

    @Test
    void recordsOnlyRedactedRequestEvidence() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            post(client, pg, body(pg), "135135");
            LocalTestPg.CapturedRequest request = pg.requests().getFirst();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.rawPath()).isEqualTo("/api/v1/payments");
            assertThat(request.rawQuery()).isNull();
            assertThat(request.account()).isEqualTo("135135");
            assertThat(request.fieldTypes()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "orderId", "STRING", "cardType", "STRING", "cardNo", "STRING",
                "amount", "NUMBER", "callbackUrl", "STRING"));
            assertThat(request.orderId()).isEqualTo(ORDER);
            assertThat(request.amount()).isEqualTo(7000L);
            assertThat(request.cardType()).isEqualTo("TEST");
            assertThat(request.callbackUrl()).isEqualTo(pg.callbackUri().toString());
            assertThat(request.cardMatchesExpected()).isTrue();
            assertThat(request.toString()).doesNotContain("0000-0000-0000-0000");
            assertThat(pg.requests()).hasSize(1);
        }
    }

    @Test
    void fixesTheSyntheticProviderAccountRatherThanAcceptingCustomerIdentity() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            assertThat(post(client, pg, body(pg), "customer-1").statusCode()).isEqualTo(401);
            HttpRequest request = HttpRequest.newBuilder(pg.baseUri().resolve("/api/v1/payments?orderId=" + ORDER))
                .header("X-USER-ID", "customer-1").timeout(Duration.ofSeconds(2)).GET().build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
            assertThat(pg.transactionCount()).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "   ",
        "{\"orderId\":\"short\",\"cardType\":\"TEST\",\"cardNo\":\"0\",\"amount\":7000,\"callbackUrl\":\"local\"}",
        "{\"orderId\":\"LP-ORD-42\",\"cardType\":\"TEST\",\"cardNo\":\"0\",\"amount\":\"7000\",\"callbackUrl\":\"local\"}",
        "{\"orderId\":\"LP-ORD-42\",\"cardType\":\"TEST\",\"cardNo\":\"0\",\"amount\":1.5,\"callbackUrl\":\"local\"}",
        "{\"orderId\":\"LP-ORD-42\",\"cardType\":\"TEST\",\"cardNo\":\"0\",\"amount\":-1,\"callbackUrl\":\"local\"}",
        "{\"orderId\":\"LP-ORD-42\",\"cardType\":\"TEST\",\"cardNo\":null,\"amount\":7000,\"callbackUrl\":\"local\"}",
        "{\"orderId\":\"LP-ORD-42\",\"cardType\":\" \",\"cardNo\":\"0\",\"amount\":7000,\"callbackUrl\":\"local\"}",
        "{\"orderId\":\"LP-ORD-42\",\"cardType\":\"TEST\",\"cardNo\":\"0\",\"amount\":7000,\"callbackUrl\":false}"
    })
    void rejectsMalformedRequestFieldsWithoutAnExternalEffect(String malformed) throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            assertThat(post(client, pg, malformed, "135135").statusCode()).isEqualTo(400);
            assertThat(pg.transactionCount()).isZero();
        }
    }

    @Test
    void customReplyIsOneShotAndDoesNotPretendItCreatedATransaction() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            pg.replyNext(503, "{\"status\":\"unavailable\"}");
            assertThat(post(client, pg, body(pg), "135135").statusCode()).isEqualTo(503);
            assertThat(pg.transactionCount()).isZero();
            assertThat(post(client, pg, body(pg), "135135").statusCode()).isEqualTo(202);
            assertThat(pg.transactionCount()).isEqualTo(1);
        }
    }

    @Test
    void canHoldALookupResponseUntilTheTestReleasesIt() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            pg.replyNextHeld(200, "[]");
            HttpRequest request = HttpRequest.newBuilder(pg.baseUri().resolve("/api/v1/payments?orderId=" + ORDER))
                .header("X-USER-ID", "135135").timeout(Duration.ofSeconds(2)).GET().build();
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertThat(pg.awaitRequest(Duration.ofSeconds(2))).isTrue();
            assertThat(pg.lookupCalls()).isEqualTo(1);
            assertThat(response.isDone()).isFalse();
            assertThat(pg.transactionCount()).isZero();
            pg.releaseResponses();
            HttpResponse<String> released = response.get(2, TimeUnit.SECONDS);
            assertThat(released.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(released.body())).isEmpty();
        }
    }

    @Test
    void canHoldAfterResponseHeadersAndTheFirstBodyByte() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            pg.replyNextHeldBody(200, "[]");
            HttpRequest request = HttpRequest.newBuilder(pg.baseUri().resolve("/api/v1/payments?orderId=" + ORDER))
                .header("X-USER-ID", "135135").timeout(Duration.ofSeconds(2)).GET().build();
            var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            HttpResponse<java.io.InputStream> headersReceived = response.get(2, TimeUnit.SECONDS);
            try (var remaining = headersReceived.body()) {
                assertThat(pg.awaitBody(Duration.ofSeconds(2))).isTrue();
                assertThat(headersReceived.statusCode()).isEqualTo(200);
                assertThat(pg.lookupCalls()).isEqualTo(1);
                assertThat(pg.transactionCount()).isZero();
                assertThat(remaining.read()).isEqualTo('[');
                pg.releaseResponses();
                assertThat(new String(remaining.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("]");
            }
        }
    }

    @Test
    void recordsEncodedOrderQueryAndDecodesItForLookup() throws Exception {
        try (LocalTestPg pg = new LocalTestPg(); HttpClient client = client()) {
            String providerOrder = "LP-ORDER &+한글";
            post(client, pg, body(pg).replace(ORDER, providerOrder), "135135");
            String query = "orderId=" + java.net.URLEncoder.encode(providerOrder, StandardCharsets.UTF_8);
            HttpResponse<String> response = get(client, pg.baseUri().resolve("/api/v1/payments?" + query));
            assertThat(JSON.readTree(response.body())).hasSize(1);
            assertThat(pg.requests().getLast().rawQuery()).isEqualTo(query);
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static String body(LocalTestPg pg) throws Exception {
        return JSON.writeValueAsString(Map.of("orderId", ORDER, "cardType", "TEST",
            "cardNo", "0000-0000-0000-0000", "amount", 7000, "callbackUrl", pg.callbackUri().toString()));
    }

    private static HttpResponse<String> post(HttpClient client, LocalTestPg pg, String body, String account)
        throws Exception {
        return client.send(postRequest(pg, body, account), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpRequest postRequest(LocalTestPg pg, String body, String account) {
        return HttpRequest.newBuilder(pg.baseUri().resolve("/api/v1/payments"))
            .header("X-USER-ID", account).header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(2)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).header("X-USER-ID", "135135")
            .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
