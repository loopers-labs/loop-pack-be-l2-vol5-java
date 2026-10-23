package com.loopers.testpg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Extension;

import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

public final class TestPgApplication {

    private TestPgApplication() {
    }

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("TEST_PG_PORT", "8080"));
        String bindAddress = System.getenv().getOrDefault("TEST_PG_BIND_ADDRESS", "0.0.0.0");
        ObjectMapper objectMapper = new ObjectMapper();
        Path dataFile = Path.of(System.getenv().getOrDefault(
            "TEST_PG_DATA_FILE", "build/test-pg/transactions.json"));
        var store = new TransactionStore(dataFile, objectMapper);
        String secretKey = System.getenv().getOrDefault("TEST_PG_SECRET_KEY", "local-secret");
        Extension api = new TestPgResponseTransformer(store, objectMapper, secretKey);
        WireMockServer server = new WireMockServer(WireMockConfiguration.options()
            .bindAddress(bindAddress)
            .port(port)
            .extensions(api));

        server.start();
        server.stubFor(post(urlEqualTo("/v1/payments/confirm"))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        server.stubFor(post(urlPathMatching("/v1/payments/[^/]+/cancel"))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        server.stubFor(get(urlPathMatching("/v1/payments/orders/[^/]+"))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        server.stubFor(get(urlPathMatching("/v1/payments/[^/]+"))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        server.stubFor(post(urlEqualTo("/__test/payments/status"))
            .willReturn(aResponse().withStatus(404).withTransformers("local-test-pg-api")));
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        System.out.printf("Local test PG listening on %s:%d%n", bindAddress, port);
    }
}
