package com.loopers.support.pg;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class PgFixtureServer {

    private static final int DEFAULT_PG_PORT = 8090;
    private static final int DEFAULT_CONTROL_PORT = 8091;
    private static final AtomicInteger CALLBACKS = new AtomicInteger();

    private PgFixtureServer() {
    }

    public static void main(String[] args) throws IOException {
        int pgPort = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PG_PORT;
        int controlPort = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_CONTROL_PORT;
        PgFixture fixture = PgFixture.start(pgPort);

        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 0);
        control.createContext("/approve", exchange -> approve(fixture, exchange));
        control.createContext("/stats", exchange -> stats(fixture, exchange));
        control.createContext("/drop-next", exchange -> dropNext(fixture, exchange));
        control.createContext("/succeed", exchange -> succeed(fixture, exchange));
        control.start();
        System.out.printf("PG fixture %s, control http://127.0.0.1:%d%n", fixture.baseUrl(), controlPort);
    }

    private static void stats(PgFixture fixture, HttpExchange exchange) throws IOException {
        String body = "create=" + fixture.createCount() + " lookup=" + fixture.lookupCount()
            + " callback=" + CALLBACKS.get() + " transactions=" + fixture.transactionCount();
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
        exchange.close();
    }

    private static void dropNext(PgFixture fixture, HttpExchange exchange) throws IOException {
        fixture.dropNextCreateResponse();
        respond(exchange, 200, "다음 생성 호출은 거래를 저장한 뒤 응답 없이 연결을 닫는다");
    }

    private static void succeed(PgFixture fixture, HttpExchange exchange) throws IOException {
        String orderId = exchange.getRequestURI().getQuery().replaceFirst("^orderId=", "");
        List<PgFixture.Transaction> transactions = fixture.transactionsOf(orderId);
        transactions.forEach(transaction ->
            fixture.changeStatus(transaction.transactionKey(), PgFixture.Status.SUCCESS));
        String body = transactions.stream()
            .map(transaction -> transaction.transactionKey() + " SUCCESS callback=none")
            .collect(Collectors.joining("\n"));
        respond(exchange, transactions.isEmpty() ? 404 : 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private static void approve(PgFixture fixture, HttpExchange exchange) throws IOException {
        String orderId = exchange.getRequestURI().getQuery().replaceFirst("^orderId=", "");
        List<PgFixture.Transaction> transactions = fixture.transactionsOf(orderId);
        String body = transactions.stream()
            .map(transaction -> {
                fixture.changeStatus(transaction.transactionKey(), PgFixture.Status.SUCCESS);
                CALLBACKS.incrementAndGet();
                return transaction.transactionKey() + " callback=" + fixture.sendCallback(transaction.transactionKey());
            })
            .collect(Collectors.joining("\n"));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(transactions.isEmpty() ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }
}
