package com.loopers.fixture;

import com.loopers.fixture.pg.PgFixtureServer;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;

public class PgFixtureContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        } catch (IOException failure) {
            throw new IllegalStateException("commerce 테스트 포트 선택 실패", failure);
        }
        PgFixtureServer pg = PgFixtureServer.start();
        DefaultListableBeanFactory beans = (DefaultListableBeanFactory) context.getBeanFactory();
        beans.registerSingleton("testPgFixture", pg);
        beans.registerDisposableBean("testPgFixture", pg::close);
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("testPgFixture", Map.of(
            "commerce.pg.base-url", pg.baseUrl().toString(),
            "server.port", port,
            "commerce.pg.callback-url", "http://127.0.0.1:" + port + "/api/v1/payments/callback")));
    }
}
