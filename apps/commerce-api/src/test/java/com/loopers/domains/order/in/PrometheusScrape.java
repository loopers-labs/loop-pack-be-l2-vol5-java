package com.loopers.domains.order.in;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 management 포트의 /actuator/prometheus 원문을 받아 미확인 결제 gauge 한 줄의 값을 읽는다. 서버는 127.0.0.1에 바인딩된다.
final class PrometheusScrape {
    static final String UNRESOLVED_METRIC = "commerce_payment_unresolved";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();

    private PrometheusScrape() {
    }

    static String scrape(int managementPort) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + managementPort + "/actuator/prometheus"))
            .timeout(Duration.ofSeconds(2))
            .GET()
            .build();
        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("prometheus 응답 코드").isEqualTo(200);
        return response.body();
    }

    static Optional<Double> unresolved(String body, String status) {
        String label = "status=\"" + status + "\"";
        return body.lines()
            .filter(line -> line.startsWith(UNRESOLVED_METRIC + "{") && line.contains(label))
            .map(line -> Double.parseDouble(line.substring(line.lastIndexOf('}') + 1).trim().split(" ")[0]))
            .findFirst();
    }
}
