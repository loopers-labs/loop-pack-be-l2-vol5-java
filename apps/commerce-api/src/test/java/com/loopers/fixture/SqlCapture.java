package com.loopers.fixture;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hibernate 가 준비하는 SQL 문장을 기록 구간 동안 모은다.
 * {@code spring.jpa.properties.hibernate.session_factory.statement_inspector} 로 등록한 테스트에서만 동작한다.
 */
public class SqlCapture implements StatementInspector {

    public static final String PROPERTY =
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.loopers.fixture.SqlCapture";

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();
    private static volatile boolean recording;

    public static void start() {
        STATEMENTS.clear();
        recording = true;
    }

    public static List<String> stop() {
        recording = false;
        return List.copyOf(STATEMENTS);
    }

    @Override
    public String inspect(String sql) {
        if (recording) {
            STATEMENTS.add(sql);
        }
        return sql;
    }
}
