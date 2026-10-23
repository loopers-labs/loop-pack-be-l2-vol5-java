package com.loopers.support.pg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public final class PgFixtureServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PgFixtureServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        int adminPort = args.length > 1 ? Integer.parseInt(args[1]) : 8091;
        PgFixture pg = PgFixture.start(port);
        pg.recordRequests(false);
        HttpServer admin = HttpServer.create(new InetSocketAddress("127.0.0.1", adminPort), 0);
        admin.setExecutor(Executors.newFixedThreadPool(2));
        admin.createContext("/fixture", exchange -> route(pg, exchange));
        admin.start();
        System.out.printf("PG fixture %s, admin http://127.0.0.1:%d/fixture%n", pg.baseUrl(), adminPort);
    }

    static void route(PgFixture pg, HttpExchange exchange) throws IOException {
        try (exchange) {
            Map<String, String> query = queryOf(exchange);
            String action = exchange.getRequestURI().getPath().substring("/fixture/".length());
            switch (action) {
                case "create-delay" -> pg.delayCreates(Duration.ofMillis(Long.parseLong(query.get("ms"))));
                case "lookup-delay" -> pg.delayLookups(Duration.ofMillis(Long.parseLong(query.get("ms"))));
                case "lookup-fail" -> pg.failEveryNthLookup(
                    Integer.parseInt(query.get("every")), Integer.parseInt(query.getOrDefault("status", "500")));
                case "auto-settle" -> pg.settleAutomatically(
                    "NONE".equals(query.get("status")) ? null : PgFixture.Status.valueOf(query.get("status")),
                    Duration.ofMillis(Long.parseLong(query.getOrDefault("after-ms", "0"))));
                case "reset" -> pg.reset();
                case "stats" -> {
                    respond(exchange, 200, JSON.writeValueAsBytes(pg.stats()));
                    return;
                }
                default -> {
                    respond(exchange, 404, ("unknown action: " + action).getBytes(StandardCharsets.UTF_8));
                    return;
                }
            }
            respond(exchange, 200, "ok".getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            respond(exchange, 400, String.valueOf(e).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Map<String, String> queryOf(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) {
            return Map.of();
        }
        return Arrays.stream(query.split("&"))
            .map(pair -> pair.split("=", 2))
            .collect(Collectors.toMap(pair -> pair[0], pair -> pair.length > 1 ? pair[1] : ""));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
