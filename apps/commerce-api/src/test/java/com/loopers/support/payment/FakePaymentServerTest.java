package com.loopers.support.payment;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class FakePaymentServerTest {

    @Test
    void rejectedSecondCreateCountsEvenWhenTheTotalMatchesTwoOrders() throws Exception {
        try (var pg = new FakePaymentServer(); var client = HttpClient.newHttpClient()) {
            var request = createPayment(pg, "LP-ORD-A");
            var first = client.send(request, HttpResponse.BodyHandlers.ofString());
            pg.replyNext(422, "");
            var second = client.send(request, HttpResponse.BodyHandlers.ofString());
            var lookup = HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments?orderId=LP-ORD-A")).GET().build();
            assertThat(client.send(lookup, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

            var counts = pg.createCallsByOrder();
            System.out.printf("[duplicate-create] first=%d second=%d pgCreates=%d pgLookups=%d transactions=%d byOrder=%s%n",
                first.statusCode(), second.statusCode(), pg.createCalls(), pg.lookupCalls(), pg.transactionCount(), counts);
            assertThat(first.statusCode()).isEqualTo(202);
            assertThat(second.statusCode()).isEqualTo(422);
            assertThat(pg.createCalls()).isEqualTo(2);
            assertThat(pg.transactionCount()).isOne();
            assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("LP-ORD-A", 2));
        }
    }

    @Test
    void anotherOrderGetsAcceptedWhileSelectedResponseWaitsForRelease() throws Exception {
        try (var pg = new FakePaymentServer(); var client = HttpClient.newHttpClient()) {
            pg.holdCreateResponseFor("LP-ORD-A");
            assertThat(pg.heldCreateOrders()).containsExactly("LP-ORD-A");
            var selected = client.sendAsync(createPayment(pg, "LP-ORD-A"), HttpResponse.BodyHandlers.ofString());
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    while (pg.transactionCount() == 0) {
                        Thread.sleep(5);
                    }
                });
                var normal = client.send(createPayment(pg, "LP-ORD-B"), HttpResponse.BodyHandlers.ofString());
                assertThat(normal.statusCode()).isEqualTo(202);
                assertThat(selected).isNotDone();
            } finally {
                pg.releaseCreateResponseFor("LP-ORD-A");
            }
            assertThat(selected.get(2, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
            assertThat(pg.heldCreateOrders()).isEmpty();
            assertThat(pg.transactionCount()).isEqualTo(2);
            assertThat(pg.createCalls()).isEqualTo(2);
        }
    }

    private HttpRequest createPayment(FakePaymentServer pg, String orderId) {
        return HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments"))
            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(7))
            .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"" + orderId + "\",\"amount\":1000}")).build();
    }

    @Test
    void periodicLookupDelayLeavesCreatesAndOtherLookupsFastAndCanBeClearedWithoutReset() throws Exception {
        try (var pg = new FakePaymentServer(); var client = HttpClient.newHttpClient()) {
            HttpRequest create = HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"LP-DELAY-1\",\"amount\":1000}")).build();
            assertThat(client.send(create, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
            pg.delayEveryNthLookup(2, Duration.ofSeconds(1));
            HttpRequest lookup = HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments/TEST-TX-1")).GET().build();
            assertThat(client.send(lookup, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            var delayed = client.sendAsync(lookup, HttpResponse.BodyHandlers.ofString());
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (pg.lookupCalls() < 2) {
                    Thread.sleep(5);
                }
            });
            assertThat(client.send(create, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(202);
            assertThat(client.send(lookup, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(delayed).isNotDone();
            assertThat(delayed.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(pg.patternLookupCalls()).isEqualTo(3);
            assertThat(pg.delayedLookupCalls()).isEqualTo(1);

            pg.clearQueuedFaults();
            assertThat(client.send(lookup, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(pg.transactionCount()).isEqualTo(2);
            assertThat(pg.patternLookupCalls()).isEqualTo(3);
            pg.reset();
            assertThat(pg.patternLookupCalls()).isZero();
            assertThat(pg.delayedLookupCalls()).isZero();
        }
    }

    @Test
    void delayedRequestKeepsItsAssignedReplyWhileAnotherRequestFinishesFirst() throws Exception {
        try (var pg = new FakePaymentServer(); var client = HttpClient.newHttpClient()) {
            pg.delayNextResponse(Duration.ofSeconds(1));
            pg.replyNext(503, "first");
            pg.replyNext(502, "second");
            HttpRequest request = HttpRequest.newBuilder(URI.create(pg.baseUrl() + "/api/v1/payments/TEST-TX-1"))
                .timeout(Duration.ofSeconds(5)).GET().build();

            var delayed = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (pg.lookupCalls() == 0) {
                    Thread.sleep(5);
                }
            });

            var second = client.send(request, HttpResponse.BodyHandlers.ofString());

            // 두 요청이 실제로 겹쳤는지도 확인해 순차 실행으로 우연히 통과하지 않게 한다.
            assertThat(delayed).isNotDone();
            assertThat(second.statusCode()).isEqualTo(502);
            assertThat(second.body()).isEqualTo("second");
            var first = delayed.get(5, TimeUnit.SECONDS);
            assertThat(first.statusCode()).isEqualTo(503);
            assertThat(first.body()).isEqualTo("first");
            assertThat(pg.lookupCalls()).isEqualTo(2);
        }
    }
}
