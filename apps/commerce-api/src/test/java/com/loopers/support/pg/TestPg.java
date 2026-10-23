package com.loopers.support.pg;

import com.github.tomakehurst.wiremock.WireMockServer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Spring 테스트가 붙는 테스트 PG(docs/week5/design.md D-033). JVM마다 하나만 띄운다 — 주소가 하나로 고정돼야
 * 테스트 컨텍스트 캐시가 깨지지 않는다(주소는 {@link TestPgInitializer}가 넣는다).
 *
 * <p>기본 응답: 모든 거래 생성 요청을 202 PENDING으로 받는다. 요청의 orderId·amount를 그대로 돌려줘야 adapter가
 * 수락으로 인정하므로 응답 템플릿으로 되돌려 준다. 조회는 기본 stub이 없어 404다. 테스트 메서드마다
 * {@link TestPgResetListener}가 이 상태로 되돌리므로, 다른 응답이 필요한 테스트는 {@link #server()}에 stub을 더한다.
 */
public final class TestPg {

    public static final String PAYMENTS_PATH = "/api/v1/payments";

    private static final WireMockServer SERVER = start();

    private TestPg() {}

    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        acceptAll(server);
        warmUp(server);
        return server;
    }

    /**
     * 첫 요청은 WireMock의 응답 템플릿 엔진을 초기화하느라 느려, 테스트용 짧은 create timeout(500ms)을 넘길 수 있다.
     * 그러면 어느 테스트가 JVM에서 처음 PG를 부르느냐에 따라 결과가 달라진다 — 띄울 때 한 번 불러 데워 두고 기록은 지운다.
     */
    private static void warmUp(WireMockServer server) {
        try {
            HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + PAYMENTS_PATH))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"LP-ORD-WARMUP\",\"amount\":0}"))
                    .build(),
                HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new IllegalStateException("테스트 PG 준비 실패", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트 PG 준비 중단", e);
        }
        server.resetRequests();
    }

    public static WireMockServer server() {
        return SERVER;
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }

    public static void reset() {
        SERVER.resetAll();
        acceptAll(SERVER);
    }

    private static void acceptAll(WireMockServer server) {
        server.stubFor(post(urlEqualTo(PAYMENTS_PATH))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"transactionKey\":\"TEST-TX-{{jsonPath request.body '$.orderId'}}\","
                    + "\"orderId\":\"{{jsonPath request.body '$.orderId'}}\","
                    + "\"amount\":{{jsonPath request.body '$.amount'}},\"status\":\"PENDING\"}")
                .withTransformers("response-template")));
    }

    /** 기본 응답으로 받은 거래의 transactionKey. */
    public static String transactionKeyOf(String providerOrderId) {
        return "TEST-TX-" + providerOrderId;
    }
}
