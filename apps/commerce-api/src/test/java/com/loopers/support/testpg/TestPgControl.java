package com.loopers.support.testpg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// test-pg의 테스트 전용 제어 API를 호출한다.
public final class TestPgControl {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private TestPgControl() {}

    public static void lookupDelay(long delayMillis) {
        HttpResponse<String> response = send("PUT", "/test-control/v1/lookup", "{\"delayMillis\":" + delayMillis + "}");
        if (response.statusCode() != 204) {
            throw new IllegalStateException("조회 지연 설정 실패: " + response.statusCode());
        }
    }

    public static void lookupFailures(int failures) {
        HttpResponse<String> response = send("PUT", "/test-control/v1/lookup",
            "{\"delayMillis\":0,\"failureCount\":" + failures + ",\"failureStatus\":503}");
        if (response.statusCode() != 204) {
            throw new IllegalStateException("조회 실패 설정 오류: " + response.statusCode());
        }
    }

    public static void scenario(String providerOrderId, String scenarioJson) {
        HttpResponse<String> response = send("PUT", "/test-control/v1/orders/" + providerOrderId + "/scenario", scenarioJson);
        if (response.statusCode() != 204) {
            throw new IllegalStateException("시나리오 등록 실패: " + response.statusCode() + " " + response.body());
        }
    }

    public static int complete(String providerOrderId) {
        return read(send("POST", "/test-control/v1/orders/" + providerOrderId + "/complete", null)).path("completed").asInt();
    }

    public static Stats stats(String providerOrderId) {
        JsonNode body = read(send("GET", "/test-control/v1/orders/" + providerOrderId + "/stats", null));
        return new Stats(body.path("createRequests").asInt(), body.path("lookupRequests").asInt(),
            body.path("callbackAttempts").asInt(), body.path("transactions").asInt());
    }

    // 주문 단위 거래 조회의 원시 응답 본문이다.
    public static String lookupBodyByOrder(String providerOrderId) {
        return send("GET", "/api/v1/payments?orderId=" + providerOrderId, null).body();
    }

    private static JsonNode read(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static HttpResponse<String> send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(TestPgContainer.baseUrl() + path))
            .timeout(Duration.ofSeconds(5)).header("X-USER-ID", TestPgContainer.PROVIDER_ACCOUNT_ID);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        try {
            return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record Stats(int createRequests, int lookupRequests, int callbackAttempts, int transactions) {}
}
