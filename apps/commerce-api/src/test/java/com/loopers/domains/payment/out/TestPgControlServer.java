package com.loopers.domains.payment.out;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

public final class TestPgControlServer implements AutoCloseable {
    static final String CONTROL_PATH = "/control";

    private final TestPgServer testPgServer;
    private final HttpServer server;

    public TestPgControlServer(TestPgServer testPgServer, int port) throws IOException {
        this.testPgServer = testPgServer;
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        this.server.createContext(CONTROL_PATH, this::handle);
        this.server.start();
    }

    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(501, -1);
        }
    }
}
