package com.loopers.support.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * 부하 실험에서 가짜 PG를 앱과 별도 프로세스로 띄운다. 결제 API는 PG 포트, 장애 조작과 통계는 제어 포트에 둔다.
 * <pre>
 * POST /delay?ms=400            모든 응답을 늦춘다. ms=0이면 해제
 * POST /fail?status=503&count=N  다음 요청 N개에 지정한 상태 코드로 답한다
 * POST /reset                    거래·호출 수·장애 설정·통계를 비운다
 * GET  /stats                    받은 생성·조회 수와 PG가 직접 잰 처리 시간(p50·p99·max, ms)
 * </pre>
 * 실행: {@code ./gradlew :apps:commerce-api:runFakePg -PpgPort=8090 -PcontrolPort=8091}
 */
public final class FakePaymentServerMain {

    private FakePaymentServerMain() {}

    public static void main(String[] args) throws IOException {
        int pgPort = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        int controlPort = args.length > 1 ? Integer.parseInt(args[1]) : 8091;
        FakePaymentServer pg = new FakePaymentServer(pgPort);

        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 0);
        control.createContext("/delay", exchange -> {
            long millis = Long.parseLong(query(exchange).getOrDefault("ms", "0"));
            pg.delayEveryResponse(millis > 0 ? Duration.ofMillis(millis) : null);
            reply(exchange, "{\"delayMs\":" + millis + "}");
        });
        control.createContext("/fail", exchange -> {
            Map<String, String> query = query(exchange);
            int status = Integer.parseInt(query.getOrDefault("status", "503"));
            int count = Integer.parseInt(query.getOrDefault("count", "1"));
            for (int i = 0; i < count; i++) {
                pg.replyNext(status, "");
            }
            reply(exchange, "{\"status\":" + status + ",\"count\":" + count + "}");
        });
        control.createContext("/reset", exchange -> {
            pg.reset();
            reply(exchange, "{\"reset\":true}");
        });
        control.createContext("/stats", exchange -> reply(exchange, stats(pg)));
        control.createContext("/lookupDelay", exchange -> {
            Map<String, String> query = query(exchange);
            pg.delayEveryNthLookup(Integer.parseInt(query.getOrDefault("every", "5")),
                Duration.ofMillis(Long.parseLong(query.getOrDefault("ms", "800"))));
            reply(exchange, "{\"configured\":true}");
        });
        control.createContext("/down", exchange -> {
            pg.down();
            reply(exchange, "{\"running\":false}");
        });
        control.createContext("/up", exchange -> {
            pg.up();
            reply(exchange, "{\"running\":true}");
        });
        control.createContext("/complete", exchange -> {
            pg.completeAll(query(exchange).getOrDefault("status", "SUCCESS"));
            reply(exchange, "{\"completed\":true}");
        });
        control.createContext("/clearFaults", exchange -> {
            pg.clearQueuedFaults();
            pg.delayEveryResponse(null);
            reply(exchange, "{\"cleared\":true}");
        });
        control.createContext("/transactions", exchange ->
            reply(exchange, new ObjectMapper().writeValueAsString(pg.transactions())));
        control.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        control.start();

        System.out.printf("fake PG: %s, control: http://127.0.0.1:%d%n", pg.baseUrl(), control.getAddress().getPort());
    }

    private static String stats(FakePaymentServer pg) throws IOException {
        List<FakePaymentServer.Handled> handled = pg.handled();
        var orders = pg.createCallsByOrder().keySet();
        ObjectMapper mapper = new ObjectMapper();
        return "{\"createCalls\":" + pg.createCalls() + ",\"lookupCalls\":" + pg.lookupCalls()
            + ",\"transactionCount\":" + pg.transactionCount() + ",\"distinctCreateOrders\":" + orders.size()
            + ",\"createOrderIds\":" + mapper.writeValueAsString(orders)
            + ",\"patternLookupCalls\":" + pg.patternLookupCalls()
            + ",\"delayedLookupCalls\":" + pg.delayedLookupCalls()
            + ",\"create\":" + summary(handled, "POST") + ",\"lookup\":" + summary(handled, "GET") + "}";
    }

    private static String summary(List<FakePaymentServer.Handled> handled, String method) {
        List<Long> millis = handled.stream().filter(h -> h.method().equals(method))
            .map(h -> h.duration().toMillis()).sorted(Comparator.naturalOrder()).toList();
        if (millis.isEmpty()) {
            return "{\"count\":0}";
        }
        return "{\"count\":" + millis.size() + ",\"p50Ms\":" + percentile(millis, 0.50)
            + ",\"p99Ms\":" + percentile(millis, 0.99) + ",\"maxMs\":" + millis.getLast() + "}";
    }

    private static long percentile(List<Long> sorted, double rank) {
        int index = (int) Math.ceil(rank * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return values;
        }
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static void reply(HttpExchange exchange, String json) throws IOException {
        try (exchange) {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
