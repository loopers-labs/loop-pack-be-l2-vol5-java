package com.loopers.support.payment;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class FakePaymentServerTest {

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
