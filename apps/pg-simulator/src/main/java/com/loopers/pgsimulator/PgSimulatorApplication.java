package com.loopers.pgsimulator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

/**
 * 수동 실행 진입점(설계 2.10). 인자: --port=18080 --settlement-delay-ms=1000 (모두 생략 가능).
 * 금액 규칙과 자동 처리로 시작하며, 주소는 127.0.0.1 고정이다.
 */
public final class PgSimulatorApplication {

    static final int DEFAULT_PORT = 18080;
    static final long DEFAULT_SETTLEMENT_DELAY_MS = 1000;

    private static final Logger log = LoggerFactory.getLogger(PgSimulatorApplication.class);
    private static final String PORT_ARG = "--port=";
    private static final String DELAY_ARG = "--settlement-delay-ms=";

    private PgSimulatorApplication() {
    }

    public static void main(String[] args) {
        PgSimulatorOptions options;
        try {
            options = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("pg-simulator 시작 실패: " + e.getMessage());
            System.err.println("사용법: --port=18080 --settlement-delay-ms=1000");
            System.exit(1);
            return;
        }
        PgSimulator pg = PgSimulator.start(options);
        Runtime.getRuntime().addShutdownHook(new Thread(pg::close, "pg-simulator-shutdown"));
        log.info("pg-simulator 시작: baseUrl={}, 자동 처리 지연={}ms, 결과 규칙=amount % 3 (0 PENDING→SUCCESS, 1 즉시 SUCCESS, 2 PENDING→FAILED)",
            pg.baseUrl(), options.settlement().delay().toMillis());
    }

    static PgSimulatorOptions parse(String[] args) {
        int port = DEFAULT_PORT;
        long delayMs = DEFAULT_SETTLEMENT_DELAY_MS;
        for (String arg : args) {
            if (arg.startsWith(PORT_ARG)) {
                port = parseNumber(arg.substring(PORT_ARG.length()), "port");
                if (port < 1 || port > 65535) {
                    throw new IllegalArgumentException("port 는 1~65535: " + port);
                }
            } else if (arg.startsWith(DELAY_ARG)) {
                delayMs = parseNumber(arg.substring(DELAY_ARG.length()), "settlement-delay-ms");
                if (delayMs < 0) {
                    throw new IllegalArgumentException("settlement-delay-ms 는 0 이상: " + delayMs);
                }
            } else {
                throw new IllegalArgumentException("알 수 없는 인자: " + arg);
            }
        }
        return new PgSimulatorOptions(port, new AmountModuloOutcomePolicy(), Settlement.after(Duration.ofMillis(delayMs)));
    }

    private static int parseNumber(String value, String name) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 는 정수: " + value);
        }
    }
}
