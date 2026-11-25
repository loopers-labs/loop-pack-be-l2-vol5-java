package com.loopers.domains.payment.out;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

// 사람 흐름 리허설 전용 실행 진입점. Gradle runTestPg(test 런타임 classpath)로만 띄우며 운영 jar에는 들어가지 않는다.
// 앱 local 설정(payment.providers.test-pg)의 주소·사용자 ID와 맞춘다. 유실 지연은 앱 read-timeout(3s)보다 길어야 결과 모름이 된다.
public final class TestPgServerMain {
    private static final String DEFAULT_USER_ID = "135135";
    private static final int DEFAULT_PAYMENT_PORT = 8082;
    private static final int DEFAULT_CONTROL_PORT = 8083;
    private static final long DEFAULT_DELAY_MILLIS = 5_000;

    private TestPgServerMain() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String userId = setting("TEST_PG_USER_ID", DEFAULT_USER_ID);
        int paymentPort = Integer.parseInt(setting("TEST_PG_PORT", String.valueOf(DEFAULT_PAYMENT_PORT)));
        int controlPort = Integer.parseInt(setting("TEST_PG_CONTROL_PORT", String.valueOf(DEFAULT_CONTROL_PORT)));
        Duration delay = Duration.ofMillis(
            Long.parseLong(setting("TEST_PG_DELAY_MILLIS", String.valueOf(DEFAULT_DELAY_MILLIS))));

        TestPgServer testPg = new TestPgServer(userId, delay, paymentPort);
        TestPgControlServer control = new TestPgControlServer(testPg, controlPort);
        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            control.close();
            try {
                testPg.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stopped.countDown();
        }));
        System.out.println("test-pg 결제: " + testPg.baseUrl() + ", 제어: " + control.baseUrl()
            + ", 유실 지연: " + delay.toMillis() + "ms");
        stopped.await();
    }

    private static String setting(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }
}
